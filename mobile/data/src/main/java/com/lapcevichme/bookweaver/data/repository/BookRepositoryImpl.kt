package com.lapcevichme.bookweaver.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lapcevichme.bookweaver.data.network.dto.BookManifestDto
import com.lapcevichme.bookweaver.data.network.dto.ChapterSummaryDto
import com.lapcevichme.bookweaver.data.network.dto.CharacterDto
import com.lapcevichme.bookweaver.data.network.dto.ScenarioEntryDto
import com.lapcevichme.bookweaver.data.network.mapper.toDomain
import com.lapcevichme.bookweaver.domain.model.Book
import com.lapcevichme.bookweaver.domain.model.BookDetails
import com.lapcevichme.bookweaver.domain.model.Chapter
import com.lapcevichme.bookweaver.domain.model.ChapterMedia
import com.lapcevichme.bookweaver.domain.model.PlayerChapterInfo
import com.lapcevichme.bookweaver.domain.model.ScenarioEntry
import com.lapcevichme.bookweaver.domain.repository.BookRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton


/**
 * Основная реализация репозитория. Работает с файловой системой устройства.
 */

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class BookRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient
) : BookRepository {

    private object PreferencesKeys {
        val ACTIVE_BOOK_ID = stringPreferencesKey("active_book_id")
        val ACTIVE_CHAPTER_ID = stringPreferencesKey("active_chapter_id")
    }

    // Создаем экземпляр парсера JSON.
    private val json = Json { ignoreUnknownKeys = true }

    // Корневая папка, где хранятся все распакованные книги.
    private val booksDir = File(context.filesDir, "books")

    init {
        if (!booksDir.exists()) {
            booksDir.mkdirs()
        }
    }

    override fun getLocalBooks(): Flow<List<Book>> = flow {
        val books = withContext(Dispatchers.IO) {
            booksDir.listFiles()?.filter { it.isDirectory }?.mapNotNull { bookDir ->
                val manifestFile = File(bookDir, "manifest.json")
                if (manifestFile.exists()) {
                    try {
                        val manifestDto =
                            json.decodeFromString<BookManifestDto>(manifestFile.readText())
                        val coverFile = File(bookDir, "cover.jpg")

                        Book(
                            id = bookDir.name,
                            title = manifestDto.bookName,
                            author = manifestDto.author,
                            localPath = bookDir.absolutePath,
                            coverPath = if (coverFile.exists()) coverFile.absolutePath else null
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                        null
                    }
                } else {
                    null
                }
            } ?: emptyList()
        }
        emit(books)
    }

    override suspend fun getBookDetails(bookId: String): Result<BookDetails> =
        withContext(Dispatchers.IO) {
            try {
                val bookDir = File(booksDir, bookId)
                if (!bookDir.exists()) throw Exception("Книга с id $bookId не найдена")

                val manifestFile = File(bookDir, "manifest.json")
                val manifest =
                    json.decodeFromString<BookManifestDto>(manifestFile.readText()).toDomain()

                val charactersFile = File(bookDir, "character_archive.json")
                val characters = if (charactersFile.exists()) {
                    json.decodeFromString<List<CharacterDto>>(charactersFile.readText())
                        .map { it.toDomain() }
                } else emptyList()


                val summariesFile = File(bookDir, "chapter_summaries.json")
                val summaries = if (summariesFile.exists()) {
                    json.decodeFromString<Map<String, ChapterSummaryDto>>(summariesFile.readText())
                        .mapValues { it.value.toDomain() }
                } else emptyMap()

                val chapters = bookDir.listFiles()
                    ?.filter { it.isDirectory && it.name.startsWith("vol_") }
                    ?.sortedWith(
                        compareBy(
                        { file ->
                            file.name.substringAfter("vol_").substringBefore("_chap").toIntOrNull()
                                ?: Int.MAX_VALUE
                        },
                        { file ->
                            file.name.substringAfter("chap_").toIntOrNull() ?: Int.MAX_VALUE
                        }
                    ))
                    ?.mapNotNull { chapterDir ->
                        Chapter(
                            id = chapterDir.name,
                            title = formatChapterIdToTitle(chapterDir.name),
                            audioDirectoryPath = File(chapterDir, "audio").absolutePath,
                            scenarioPath = File(chapterDir, "scenario.json").absolutePath,
                            subtitlesPath = File(
                                chapterDir,
                                "subtitles.json"
                            ).takeIf { it.exists() }?.absolutePath
                        )
                    } ?: emptyList()

                Result.success(BookDetails(manifest, characters, summaries, chapters))
            } catch (e: Exception) {
                e.printStackTrace()
                Result.failure(e)
            }
        }

    private fun formatChapterIdToTitle(id: String): String {
        return try {
            val parts = id.split("_")
            val volume = parts[1]
            val chapter = parts[3]
            "Том $volume, Глава $chapter"
        } catch (e: Exception) {
            id.replace("_", " ").replaceFirstChar { it.uppercase() }
        }
    }

    override suspend fun getScenarioForChapter(
        bookId: String,
        chapterId: String
    ): Result<List<ScenarioEntry>> = withContext(Dispatchers.IO) {
        try {
            val bookDir = File(booksDir, bookId)
            val scenarioFile = File(bookDir, "$chapterId/scenario.json")
            if (!scenarioFile.exists()) throw Exception("Scenario file not found at ${scenarioFile.path}")

            val scenario = json.decodeFromString<List<ScenarioEntryDto>>(scenarioFile.readText())
                .map { it.toDomain() }
            Result.success(scenario)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    override suspend fun downloadAndInstallBook(url: String): Result<File> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw Exception("Ошибка скачивания: ${response.code}")
                    response.body!!.byteStream().use { inputStream ->
                        return@withContext installBook(inputStream)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Result.failure(e)
            }
        }

    override suspend fun installBook(inputStream: InputStream): Result<File> =
        withContext(Dispatchers.IO) {
            // Копируем входящий поток во временный файл, чтобы его можно было читать несколько раз.
            val tempFile = File.createTempFile("install_", ".bw", context.cacheDir)
            FileOutputStream(tempFile).use { output ->
                inputStream.use { input -> input.copyTo(output) }
            }

            try {
                var bookId: String?

                // Первый проход: Ищем manifest.json и определяем ID книги (имя папки).
                ZipFile(tempFile).use { zipFile ->
                    val manifestEntry = zipFile.getEntry("manifest.json")
                        ?: return@withContext Result.failure(Exception("manifest.json не найден в архиве"))

                    val manifestContent =
                        zipFile.getInputStream(manifestEntry).bufferedReader().readText()
                    val manifest = json.decodeFromString<BookManifestDto>(manifestContent)
                    bookId = manifest.bookName.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").lowercase()
                }

                if (bookId == null) {
                    return@withContext Result.failure(Exception("Не удалось определить ID книги из манифеста"))
                }

                val bookDir = File(booksDir, bookId!!)
                if (bookDir.exists()) bookDir.deleteRecursively()
                bookDir.mkdirs()

                // Второй проход: Распаковываем все файлы в нужную директорию.
                ZipFile(tempFile).use { zipFile ->
                    for (entry in zipFile.entries()) {
                        val outputFile = File(bookDir, entry.name)
                        if (entry.isDirectory) {
                            outputFile.mkdirs()
                        } else {
                            outputFile.parentFile?.mkdirs()
                            zipFile.getInputStream(entry).use { input ->
                                FileOutputStream(outputFile).use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }
                    }
                }

                return@withContext Result.success(bookDir)
            } catch (e: Exception) {
                e.printStackTrace()
                return@withContext Result.failure(e)
            } finally {
                // 4. Очищаем временный файл.
                tempFile.delete()
            }
        }

    override suspend fun getChapterOriginalText(bookId: String, chapterId: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val (volNum, chapNum) = parseChapterId(chapterId)
                val textFile =
                    File(booksDir, "$bookId/book_source/$bookId/vol_$volNum/chapter_$chapNum.txt")


                if (!textFile.exists()) throw Exception("Original text file not found at ${textFile.path}")

                Result.success(textFile.readText())
            } catch (e: Exception) {
                e.printStackTrace()
                Result.failure(e)
            }
        }

    private fun parseChapterId(chapterId: String): Pair<String, String> {
        return try {
            val parts = chapterId.split("_")
            val volNum = parts[1]
            val chapNum = parts[3]
            volNum to chapNum
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid chapterId format: $chapterId")
        }
    }

    override suspend fun deleteBook(bookId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val bookDir = File(booksDir, bookId)
            if (bookDir.exists()) {
                val deleted = bookDir.deleteRecursively()
                if (!deleted) {
                    throw Exception("Failed to delete book directory.")
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    override fun getActiveBookIdFlow(): Flow<String?> {
        return context.dataStore.data
            .map { preferences ->
                preferences[PreferencesKeys.ACTIVE_BOOK_ID]
            }
    }

    override suspend fun setActiveBookId(bookId: String) {
        context.dataStore.edit { settings ->
            settings[PreferencesKeys.ACTIVE_BOOK_ID] = bookId
        }
    }

    override suspend fun getActiveBookId(): String? {
        return context.dataStore.data.first()[PreferencesKeys.ACTIVE_BOOK_ID]
    }

    override suspend fun getPlayerChapterInfo(
        bookId: String,
        chapterId: String
    ): Result<PlayerChapterInfo> = withContext(Dispatchers.IO) {
        try {
            // Получаем детали книги, чтобы найти нужную главу
            val bookDetails = getBookDetails(bookId).getOrThrow()
            val chapter = bookDetails.chapters.firstOrNull { it.id == chapterId }
                ?: throw Exception("Глава $chapterId не найдена в книге $bookId")

            // Проверяем, что необходимые файлы существуют
            if (chapter.subtitlesPath == null || !File(chapter.subtitlesPath).exists()) {
                throw Exception("Файл субтитров (subtitles.json) не найден для главы $chapterId")
            }
            if (!File(chapter.audioDirectoryPath).exists()) {
                throw Exception("Папка 'audio' не найдена для главы $chapterId")
            }

            val coverFile = File(booksDir, "$bookId/cover.jpg")
            val coverPath = coverFile.takeIf { it.exists() }?.absolutePath

            // Создаем новую ChapterMedia, передавая пути к ПАПКЕ и JSON
            val media = ChapterMedia(
                subtitlesPath = chapter.subtitlesPath,
                audioDirectoryPath = chapter.audioDirectoryPath
            )

            // Собираем финальную модель
            val info = PlayerChapterInfo(
                bookTitle = bookDetails.manifest.bookName,
                chapterTitle = chapter.title,
                coverPath = coverPath,
                media = media
            )
            Result.success(info)

        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }


    override suspend fun setActiveChapterId(chapterId: String) {
        context.dataStore.edit { settings ->
            settings[PreferencesKeys.ACTIVE_CHAPTER_ID] = chapterId
        }
    }

    override fun getActiveChapterIdFlow(): Flow<String?> {
        return context.dataStore.data
            .map { preferences ->
                preferences[PreferencesKeys.ACTIVE_CHAPTER_ID]
            }
    }

    override suspend fun getActiveChapterId(): String? {
        return context.dataStore.data.first()[PreferencesKeys.ACTIVE_CHAPTER_ID]
    }
}

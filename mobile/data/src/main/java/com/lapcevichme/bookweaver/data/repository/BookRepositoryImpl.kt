package com.lapcevichme.bookweaver.data.repository

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lapcevichme.bookweaver.data.dataStore
import com.lapcevichme.bookweaver.data.database.BookDao
import com.lapcevichme.bookweaver.data.database.toDomain
import com.lapcevichme.bookweaver.data.database.toEntity
import com.lapcevichme.bookweaver.data.network.dto.BookManifestDto
import com.lapcevichme.bookweaver.data.network.dto.ChapterSummaryDto
import com.lapcevichme.bookweaver.data.network.dto.CharacterDto
import com.lapcevichme.bookweaver.data.network.dto.ScenarioEntryDto
import com.lapcevichme.bookweaver.data.network.mapper.toDomain
import com.lapcevichme.bookweaver.domain.model.Book
import com.lapcevichme.bookweaver.domain.model.BookDetails
import com.lapcevichme.bookweaver.domain.model.Chapter
import com.lapcevichme.bookweaver.domain.model.ChapterMedia
import com.lapcevichme.bookweaver.domain.model.PlaybackEntry
import com.lapcevichme.bookweaver.domain.model.PlayerChapterInfo
import com.lapcevichme.bookweaver.domain.model.ScenarioEntry
import com.lapcevichme.bookweaver.domain.repository.BookRepository
import com.materialkolor.ktx.themeColor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
import kotlin.collections.map


/**
 * Основная реализация репозитория. Работает с файловой системой устройства.
 */

@Singleton
class BookRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
    private val bookDao: BookDao
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
    /*
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
     */

    override fun getLocalBooks(): Flow<List<Book>> = bookDao.getAllBooks()
        .map { entities ->
            // Маппим List<BookEntity> (из БД) в List<Book> (домен)
            entities.map { it.toDomain() }
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
                                file.name.substringAfter("vol_").substringBefore("_chap")
                                    .toIntOrNull()
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
                lateinit var manifest: BookManifestDto

                // Первый проход: Ищем manifest.json и определяем ID книги (имя папки).
                ZipFile(tempFile).use { zipFile ->
                    val manifestEntry = zipFile.getEntry("manifest.json")
                        ?: return@withContext Result.failure(Exception("manifest.json не найден в архиве"))

                    val manifestContent =
                        zipFile.getInputStream(manifestEntry).bufferedReader().readText()
                    manifest = json.decodeFromString<BookManifestDto>(manifestContent)
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

                // Нам нужен путь к обложке
                val coverFile = File(bookDir, "cover.jpg")

                // Создаем доменную модель Book, используя `manifest` из первого прохода
                val domainBook = Book(
                    id = bookId,
                    title = manifest.bookName,
                    author = manifest.author,
                    localPath = bookDir.absolutePath,
                    coverPath = if (coverFile.exists()) coverFile.absolutePath else null
                )

                // Сохраняем ее в базу данных
                bookDao.insertBook(domainBook.toEntity())


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
            bookDao.deleteBook(bookId)

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

            val bookEntity = bookDao.getBookById(bookId)
                ?: throw Exception("Книга $bookId не найдена в базе данных")

            // Получаем позицию. Если глава не совпадает, начинаем с 0.
            val lastPosition = if (bookEntity.lastListenedChapterId == chapterId) {
                bookEntity.lastListenedPosition
            } else {
                0L
            }

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
                media = media,
                lastListenedPosition = lastPosition
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

    // Цвет по умолчанию
    private val fallbackColor = Color(0xFF00668B)

    /**
     * Получает Flow с цветом для конкретной книги из БАЗЫ ДАННЫХ.
     */
    override fun getBookThemeColorFlow(bookId: String): Flow<Int?> {
        // Просто возвращаем Flow напрямую из DAO.
        // Room сам позаботится об обновлениях.
        return bookDao.getBookThemeColor(bookId)
    }

    /**
     * Генерирует и кэширует цвет для книги в БАЗУ ДАННЫХ.
     */
    override suspend fun generateAndCacheThemeColor(bookId: String, coverPath: String?) {
        withContext(Dispatchers.IO) {
            try {
                // Проверяем, есть ли цвет в кэше
                val currentColor = bookDao.getBookThemeColor(bookId).first()

                if (currentColor != null) {
                    // Цвет уже сгенерирован, ничего не делаем
                    return@withContext
                }

                // Цвета нет. Генерируем
                if (coverPath == null) return@withContext
                val coverFile = File(coverPath)
                if (!coverFile.exists()) return@withContext

                val androidBitmap = BitmapFactory.decodeFile(coverFile.absolutePath)
                if (androidBitmap == null) return@withContext

                val bitmap = androidBitmap.asImageBitmap()
                val seedColor = bitmap.themeColor(fallback = fallbackColor)
                val newColorInt = seedColor.toArgb()

                // Сохраняем в кэш (БД)
                bookDao.updateThemeColor(bookId, newColorInt)

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Сохраняет прогресс прослушивания для книги в БД.
     */
    override suspend fun saveListenProgress(bookId: String, chapterId: String, position: Long) {
        withContext(Dispatchers.IO) {
            bookDao.updateListenProgress(bookId, chapterId, position)
        }
    }

    /**
     * НОВЫЙ МЕТОД
     * Ищет путь к файлу эмбиента по его имени.
     * @param bookId ID книги
     * @param ambientName Имя файла (например, "forest.mp3" или "rain.mp3")
     * @return Result с полным путем к файлу или null, если файл не найден.
     */
    override suspend fun getAmbientTrackPath(
        bookId: String,
        ambientName: String
    ): Result<String?> = withContext(Dispatchers.IO) {
        try {
            // Если имя "none" или пустое, сразу возвращаем null
            if (ambientName.isEmpty() || ambientName.equals("none", ignoreCase = true)) {
                return@withContext Result.success(null)
            }

            // Стандартное расположение эмбиент-файлов
            val ambientFile = File(booksDir, "$bookId/ambient/$ambientName")

            if (ambientFile.exists() && ambientFile.isFile) {
                Result.success(ambientFile.absolutePath)
            } else {
                // Файл не найден
                Log.w(
                    "BookRepositoryImpl",
                    "Ambient file not found at: ${ambientFile.absolutePath}"
                )
                Result.success(null)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    /**
     * Реализация "грязной" работы по слиянию.
     * Этот метод находится в `data` слое, поэтому он МОЖЕТ
     * знать о `SubtitleEntry` из `core` и DTO-моделях.
     */
    override suspend fun getPlaybackData(
        bookId: String,
        chapterId: String
    ): Result<Pair<List<PlaybackEntry>, String>> = withContext(Dispatchers.IO) {
        try {
            val bookDir = File(booksDir, bookId)
            val chapterDir = File(bookDir, chapterId)
            if (!chapterDir.exists()) throw Exception("Chapter directory not found")

            // 1.
            val scenarioFile = File(chapterDir, "scenario.json")
            if (!scenarioFile.exists()) throw Exception("scenario.json not found")
            val scenarioDtoList = json.decodeFromString<List<ScenarioEntryDto>>(scenarioFile.readText())
            //
            val scenarioMap = scenarioDtoList.associateBy { it.id.toString() }

            // 2.
            val subtitlesFile = File(chapterDir, "subtitles.json")
            if (!subtitlesFile.exists()) throw Exception("subtitles.json not found")
            //
            val subtitleEntryList = json.decodeFromString<List<SubtitleEntry>>(subtitlesFile.readText())

            // 3.
            val audioDirectoryPath = File(chapterDir, "audio").absolutePath
            if (!File(audioDirectoryPath).exists()) throw Exception("audio directory not found")

            // 4.
            val mergedList = subtitleEntryList.map { subtitleEntry ->
                val key = subtitleEntry.audioFile.removeSuffix(".wav")
                val scenarioDto = scenarioMap[key]

                //
                val text = scenarioDto?.text ?: subtitleEntry.text
                val speaker = scenarioDto?.speaker ?: "Рассказчик"
                val ambient = scenarioDto?.ambient ?: "none"
                val emotion = scenarioDto?.emotion
                val type = scenarioDto?.type ?: "narration"

                // 5.
                PlaybackEntry(
                    id = key,
                    audioFile = subtitleEntry.audioFile,
                    text = text,
                    startMs = subtitleEntry.startMs,
                    endMs = subtitleEntry.endMs,
                    words = subtitleEntry.words.map { it.toDomain() }, //
                    speaker = speaker,
                    ambient = ambient,
                    emotion = emotion,
                    type = type
                )
            }

            Log.d("BookRepositoryImpl", "Successfully merged ${mergedList.size} playback entries.")
            Result.success(Pair(mergedList, audioDirectoryPath))

        } catch (e: Exception) {
            Log.w("BookRepositoryImpl", "getPlaybackData failed: ${e.message}")
            Result.failure(e)
        }
    }
//
//    /** * */
//    private fun WordEntry.toDomain(): DomainWordEntry {
//        return DomainWordEntry(
//            word = this.word,
//            start = this.start,
//            end = this.end
//        )
//    }

}
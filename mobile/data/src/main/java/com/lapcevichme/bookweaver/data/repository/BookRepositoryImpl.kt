package com.lapcevichme.bookweaver.data.repository

import android.content.Context
import android.net.Uri
import com.lapcevichme.bookweaver.data.network.dto.BookManifestDto
import com.lapcevichme.bookweaver.data.network.dto.ChapterSummaryDto
import com.lapcevichme.bookweaver.data.network.dto.CharacterDto
import com.lapcevichme.bookweaver.data.network.dto.ScenarioEntryDto
import com.lapcevichme.bookweaver.data.network.mapper.toDomain
import com.lapcevichme.bookweaver.domain.model.*
import com.lapcevichme.bookweaver.domain.repository.BookRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
 * Hilt будет автоматически создавать единственный экземпляр этого класса.
 */
@Singleton
class BookRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient
) : BookRepository {

    // Создаем экземпляр парсера JSON.
    private val json = Json { ignoreUnknownKeys = true }

    // Корневая папка, где хранятся все распакованные книги.
    private val booksDir = File(context.filesDir, "books")

    init {
        // Создаем папку, если ее нет.
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

    // ... остальной код класса без изменений ...

    override suspend fun getBookDetails(bookId: String): Result<BookDetails> =
        withContext(Dispatchers.IO) {
            try {
                val bookDir = File(booksDir, bookId)
                if (!bookDir.exists()) throw Exception("Книга с id $bookId не найдена")

                val manifestFile = File(bookDir, "manifest.json")
                val manifest =
                    json.decodeFromString<BookManifestDto>(manifestFile.readText()).toDomain()

                val charactersFile = File(bookDir, "character_archive.json")
                val characters =
                    json.decodeFromString<List<CharacterDto>>(charactersFile.readText())
                        .map { it.toDomain() }

                val summariesFile = File(bookDir, "chapter_summaries.json")
                val summaries =
                    json.decodeFromString<Map<String, ChapterSummaryDto>>(summariesFile.readText())
                        .mapValues { it.value.toDomain() }

                val chapters = bookDir.listFiles()
                    ?.filter { it.isDirectory && it.name.startsWith("vol_") }
                    ?.map { chapterDir ->
                        Chapter(
                            id = chapterDir.name,
                            title = formatChapterIdToTitle(chapterDir.name), // <-- ИСПРАВЛЕНО
                            audioPath = File(chapterDir, "audio").absolutePath,
                            scenarioPath = File(chapterDir, "scenario.json").absolutePath,
                            subtitlesPath = File(
                                chapterDir,
                                "subtitles.json"
                            ).takeIf { it.exists() }?.absolutePath
                        )
                    }?.sortedBy { it.id } ?: emptyList()

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


    override suspend fun getScenarioForChapter(chapter: Chapter): Result<List<ScenarioEntry>> =
        withContext(Dispatchers.IO) {
            try {
                val scenarioFile = File(chapter.scenarioPath)
                if (!scenarioFile.exists()) throw Exception("Scenario file not found at ${chapter.scenarioPath}")

                val scenario =
                    json.decodeFromString<List<ScenarioEntryDto>>(scenarioFile.readText())
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
            // 1. Копируем входящий поток во временный файл, чтобы его можно было читать несколько раз.
            val tempFile = File.createTempFile("install_", ".bw", context.cacheDir)
            FileOutputStream(tempFile).use { output ->
                inputStream.use { input -> input.copyTo(output) }
            }

            try {
                var bookId: String?

                // 2. Первый проход: Ищем manifest.json и определяем ID книги (имя папки).
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

                // 3. Второй проход: Распаковываем все файлы в нужную директорию.
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
}

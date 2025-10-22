package com.lapcevichme.bookweaver.data.repository.mock

import com.lapcevichme.bookweaver.domain.model.Book
import com.lapcevichme.bookweaver.domain.model.BookCharacter
import com.lapcevichme.bookweaver.domain.model.BookDetails
import com.lapcevichme.bookweaver.domain.model.BookManifest
import com.lapcevichme.bookweaver.domain.model.Chapter
import com.lapcevichme.bookweaver.domain.model.ChapterSummary
import com.lapcevichme.bookweaver.domain.model.PlayerChapterInfo
import com.lapcevichme.bookweaver.domain.model.ScenarioEntry
import com.lapcevichme.bookweaver.domain.repository.BookRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Моковая (тестовая) реализация репозитория книг.
 * Возвращает заранее заданные данные для разработки и тестирования UI.
 */
@Singleton
class MockBookRepository @Inject constructor() : BookRepository {

    private val mockBooks = mutableListOf(
        Book(
            id = "mock-book-1",
            title = "Монолог фармацевта: Приключения Маомао",
            localPath = "/data/data/com.lapcevichme.bookweaver/files/books/mock-book-1",
            coverPath = null // Обложки пока нет
        ),
        Book(
            id = "mock-book-2",
            title = "Стальной Алхимик: Философский камень",
            localPath = "/data/data/com.lapcevichme.bookweaver/files/books/mock-book-2",
            coverPath = null
        ),
        Book(
            id = "mock-book-3",
            title = "Атака Титанов: Падение Шиганшины",
            localPath = "/data/data/com.lapcevichme.bookweaver/files/books/mock-book-3",
            coverPath = null
        )
    )

    override fun getLocalBooks(): Flow<List<Book>> = flow {
        delay(1500) // Имитация загрузки из сети или с диска
        emit(mockBooks)
    }

    override suspend fun getBookDetails(bookId: String): Result<BookDetails> {
        val book = mockBooks.find { it.id == bookId }
            ?: return Result.failure(Exception("Mock book not found"))

        val mockManifest = BookManifest(
            bookName = book.title,
            characterVoices = emptyMap(),
            defaultNarratorVoice = "narrator_default"
        )

        val mockCharacters = listOf(
            BookCharacter(
                id = UUID.randomUUID(),
                name = "Главный Герой",
                description = "Полное описание главного героя со спойлерами.",
                spoilerFreeDescription = "Описание без спойлеров.",
                aliases = listOf("ГГ"),
                chapterMentions = emptyMap()
            )
        )

        val mockSummaries = mapOf(
            "vol_1_chap_1" to ChapterSummary(
                chapterId = "vol_1_chap_1",
                teaser = "Глава 1: Начало пути",
                synopsis = "Детальное описание событий первой главы."
            )
        )

        val mockChapters = listOf(
            Chapter(
                id = "vol_1_chap_1",
                title = "Глава 1: Начало пути",
                audioPath = "/fake/path/audio",
                scenarioPath = "/fake/path/scenario.json",
                subtitlesPath = null
            )
        )

        return Result.success(
            BookDetails(
                manifest = mockManifest,
                bookCharacters = mockCharacters,
                summaries = mockSummaries,
                chapters = mockChapters
            )
        )
    }

    override suspend fun getScenarioForChapter(bookId: String, chapterId: String): Result<List<ScenarioEntry>> {
        delay(300)
        val entries = (1..10).map {
            ScenarioEntry(
                id = UUID.randomUUID(),
                type = if (it % 2 == 0) "dialogue" else "narration",
                text = "Это строка номер $it в сценарии главы $chapterId.",
                speaker = if (it % 2 == 0) "Маомао" else "Рассказчик",
                emotion = null,
                ambient = "none",
                audioFile = null
            )
        }
        return Result.success(entries)
    }

    override fun getActiveBookIdFlow(): Flow<String?> {
        TODO("Not yet implemented")
    }

    override suspend fun setActiveBookId(bookId: String) {
        TODO("Not yet implemented")
    }

    override suspend fun getActiveBookId(): String? {
        TODO("Not yet implemented")
    }

    override suspend fun getPlayerChapterInfo(
        bookId: String,
        chapterId: String
    ): Result<PlayerChapterInfo> {
        TODO("Not yet implemented")
    }

    override suspend fun setActiveChapterId(chapterId: String) {
        TODO("Not yet implemented")
    }

    override fun getActiveChapterIdFlow(): Flow<String?> {
        TODO("Not yet implemented")
    }

    override suspend fun getActiveChapterId(): String? {
        TODO("Not yet implemented")
    }

    override suspend fun downloadAndInstallBook(url: String): Result<File> {
        delay(2000) // Симуляция долгой загрузки
        if (url.contains("fail")) {
            return Result.failure(Exception("Не удалось скачать книгу (симуляция)"))
        }

        val newBook = Book(
            id = "downloaded_${UUID.randomUUID()}",
            title = "Скачанная книга",
            localPath = "/mock/downloaded",
            coverPath = null
        )
        mockBooks.add(newBook)
        return Result.success(File(newBook.localPath))
    }

    override suspend fun installBook(inputStream: InputStream): Result<File> {
        delay(1500) // Симуляция установки

        // В моковой реализации нам не важен контент, просто симулируем успех
        val newBook = Book(
            id = "installed_${UUID.randomUUID()}",
            title = "Установленная из файла книга",
            localPath = "/mock/installed",
            coverPath = null
        )
        mockBooks.add(newBook)
        return Result.success(File(newBook.localPath))
    }

    override suspend fun getChapterOriginalText(bookId: String, chapterId: String): Result<String> {
        delay(200)
        return Result.success(
            """
            Это оригинальный текст для главы $chapterId.
            
            Здесь содержится полный, неадаптированный текст произведения. 
            Он может быть использован для сверки или просто для чтения.
            
            Lorem ipsum dolor sit amet, consectetur adipiscing elit. 
            Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua.
            """.trimIndent()
        )
    }

    override suspend fun deleteBook(bookId: String): Result<Unit> {
        delay(500)
        val removed = mockBooks.removeIf { it.id == bookId }
        return if (removed) {
            Result.success(Unit)
        } else {
            Result.failure(Exception("Книга для удаления не найдена"))
        }
    }
}

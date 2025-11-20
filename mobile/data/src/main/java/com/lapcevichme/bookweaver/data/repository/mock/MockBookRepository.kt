package com.lapcevichme.bookweaver.data.repository.mock

import com.lapcevichme.bookweaver.domain.model.Book
import com.lapcevichme.bookweaver.domain.model.BookCharacter
import com.lapcevichme.bookweaver.domain.model.BookDetails
import com.lapcevichme.bookweaver.domain.model.BookManifest
import com.lapcevichme.bookweaver.domain.model.Chapter
import com.lapcevichme.bookweaver.domain.model.ChapterMedia
import com.lapcevichme.bookweaver.domain.model.ChapterSummary
import com.lapcevichme.bookweaver.domain.model.DownloadProgress
import com.lapcevichme.bookweaver.domain.model.PlaybackEntry
import com.lapcevichme.bookweaver.domain.model.PlayerChapterInfo
import com.lapcevichme.bookweaver.domain.model.ScenarioEntry
import com.lapcevichme.bookweaver.domain.repository.BookRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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


    private val _activeBookId = MutableStateFlow<String?>("mock-book-1")
    private val _activeChapterId = MutableStateFlow<String?>("vol_1_chap_1")


    override fun getBooks(): Flow<List<Book>> = flow {

    }

    override suspend fun getBookDetails(bookId: String): Result<BookDetails> {
        return TODO("Provide the return value")
    }

    override suspend fun getCharacters(bookId: String): Result<List<BookCharacter>> {
        TODO("Not yet implemented")
    }

    override suspend fun getCharacterDetails(
        bookId: String,
        characterId: String
    ): Result<BookCharacter> {
        TODO("Not yet implemented")
    }

    override suspend fun getChapterSummary(
        bookId: String,
        chapterId: String
    ): Result<ChapterSummary> {
        TODO("Not yet implemented")
    }

    override fun downloadAndInstallBook(url: String): Flow<DownloadProgress> {
        TODO("Not yet implemented")
    }

    override suspend fun getScenarioForChapter(
        bookId: String,
        chapterId: String
    ): Result<List<ScenarioEntry>> {
        delay(300)
        val entries = (1..10).map {
            ScenarioEntry(
                id = UUID.randomUUID(),
                type = if (it % 2 == 0) "dialogue" else "narration",
                text = "Это строка номер $it в сценарии главы $chapterId.",
                speaker = if (it % 2 == 0) "Маомао" else "Рассказчик",
                emotion = null,
                ambient = "none",
                audioFile = "fake_audio_${it}.wav"
            )
        }
        return Result.success(entries)
    }

    override fun getActiveBookIdFlow(): Flow<String?> {
        return _activeBookId
    }

    override suspend fun setActiveBookId(bookId: String?) {
        TODO("Not yet implemented")
    }

    override suspend fun getActiveBookId(): String? {
        return _activeBookId.value
    }

    override suspend fun getPlayerChapterInfo(
        bookId: String,
        chapterId: String
    ): Result<PlayerChapterInfo> {
        return TODO("Provide the return value")
    }

    override suspend fun setActiveChapterId(chapterId: String?) {
        _activeChapterId.value = chapterId
    }

    override fun getActiveChapterIdFlow(): Flow<String?> {
        return _activeChapterId
    }

    override suspend fun getActiveChapterId(): String? {
        return _activeChapterId.value
    }

    override fun getBookThemeColorFlow(bookId: String): Flow<Int?> {
        TODO("Not yet implemented")
    }

    override suspend fun generateAndCacheThemeColor(
        bookId: String,
        coverPath: String?
    ) {
        TODO("Not yet implemented")
    }

    override suspend fun saveListenProgress(
        bookId: String,
        chapterId: String,
        position: Long
    ) {
        TODO("Not yet implemented")
    }

    override suspend fun getAmbientTrackPath(
        bookId: String,
        ambientName: String
    ): Result<String?> {
        TODO("Not yet implemented")
    }

    override suspend fun getPlaybackData(
        bookId: String,
        chapterId: String
    ): Result<Pair<List<PlaybackEntry>, String>> {
        TODO("Not yet implemented")
    }

    override suspend fun getLastListenedChapterId(bookId: String): String? {
        TODO("Not yet implemented")
    }

    override suspend fun syncLibraryWithRemote(): Result<Unit> {
        TODO("Not yet implemented")
    }

    override fun downloadChapter(
        bookId: String,
        chapterId: String
    ): Flow<DownloadProgress> {
        TODO("Not yet implemented")
    }

//    override suspend fun downloadAndInstallBook(url: String): Result<File> {
//        delay(2000) // Симуляция долгой загрузки
//        if (url.contains("fail")) {
//            return Result.failure(Exception("Не удалось скачать книгу (симуляция)"))
//        }
//
//        val newBook = Book(
//            id = "downloaded_${UUID.randomUUID()}",
//            title = "Скачанная книга",
//            localPath = "/mock/downloaded",
//            coverPath = null,
//            author = null
//        )
//        mockBooks.add(newBook)
//        return Result.success(File(newBook.localPath))
//    }

    override suspend fun installBook(inputStream: InputStream): Result<File> {

        return TODO("Provide the return value")
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

        return TODO("Provide the return value")
    }
}

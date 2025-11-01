package com.lapcevichme.bookweaver.domain.repository

import com.lapcevichme.bookweaver.domain.model.Book
import com.lapcevichme.bookweaver.domain.model.BookDetails
import com.lapcevichme.bookweaver.domain.model.PlayerChapterInfo
import com.lapcevichme.bookweaver.domain.model.ScenarioEntry
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.io.InputStream

interface BookRepository {
    /**
     * Получить поток (Flow) со списком всех локально сохраненных книг.
     * Flow позволяет автоматически обновлять UI при изменении данных.
     */
    fun getLocalBooks(): Flow<List<Book>>

    /**
     * Получить детальную информацию о конкретной книге по ее ID.
     * @param bookId Уникальный идентификатор книги.
     */
    suspend fun getBookDetails(bookId: String): Result<BookDetails>

    /**
     * Загружает архив книги (.bw) по URL, распаковывает и сохраняет локально.
     * @param url Ссылка на .bw файл.
     * @return Возвращает Result с путем к папке книги в случае успеха.
     */
    suspend fun downloadAndInstallBook(url: String): Result<File>

    /**
     * Устанавливает книгу из потока данных.
     * @param inputStream Поток данных .bw архива.
     */
    suspend fun installBook(inputStream: InputStream): Result<File>

    suspend fun getChapterOriginalText(bookId: String, chapterId: String): Result<String>

    /**
     * Удалить все файлы, связанные с книгой, с устройства.
     * @param bookId Уникальный идентификатор книги для удаления.
     */
    suspend fun deleteBook(bookId: String): Result<Unit>

    /**
     * Распарсить сценарий для конкретной главы.
     */
    suspend fun getScenarioForChapter(
        bookId: String,
        chapterId: String
    ): Result<List<ScenarioEntry>>

    fun getActiveBookIdFlow(): Flow<String?>
    suspend fun setActiveBookId(bookId: String)
    suspend fun getActiveBookId(): String?

    /**
     * Получает полную информацию, необходимую для запуска плеера для конкретной главы.
     */
    suspend fun getPlayerChapterInfo(bookId: String, chapterId: String): Result<PlayerChapterInfo>

    /**
     * Устанавливает ID активной главы.
     */
    suspend fun setActiveChapterId(chapterId: String)

    /**
     * Получает Flow с ID активной главы.
     */
    fun getActiveChapterIdFlow(): Flow<String?>

    /**
     * Получает ID активной главы один раз.
     */
    suspend fun getActiveChapterId(): String?

    fun getBookThemeColorFlow(bookId: String): Flow<Int?>

    suspend fun generateAndCacheThemeColor(bookId: String, coverPath: String?)

    suspend fun saveListenProgress(bookId: String, chapterId: String, position: Long)
}

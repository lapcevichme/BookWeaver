package com.lapcevichme.bookweaver.domain.repository

import com.lapcevichme.bookweaver.domain.model.Book
import com.lapcevichme.bookweaver.domain.model.BookCharacter
import com.lapcevichme.bookweaver.domain.model.BookDetails
import com.lapcevichme.bookweaver.domain.model.ChapterSummary
import com.lapcevichme.bookweaver.domain.model.DownloadProgress
import com.lapcevichme.bookweaver.domain.model.PlaybackEntry
import com.lapcevichme.bookweaver.domain.model.PlayerChapterInfo
import com.lapcevichme.bookweaver.domain.model.ScenarioEntry
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.io.InputStream

interface BookRepository {
    /**
     * Получить поток (Flow) со списком всех локально сохраненных книг.
     * Flow позволяет автоматически обновлять UI при изменении данных.
     * @return Flow со списком книг.
     */
    fun getBooks(): Flow<List<Book>>

    /**
     * Получает основную структуру книги (Манифест + Список глав).
     * Для SERVER книг теперь загружает только легкий JSON.
     * Персонажи и саммари могут быть пустыми в возвращаемом объекте.
     * @param bookId Уникальный идентификатор книги.
     * @return Result с деталями книги.
     */
    suspend fun getBookDetails(bookId: String): Result<BookDetails>

    /**
     * Получает список персонажей книги (легкий).
     * @param bookId Уникальный идентификатор книги.
     * @return Result со списком персонажей.
     */
    suspend fun getCharacters(bookId: String): Result<List<BookCharacter>>

    /**
     * Получает полную информацию о конкретном персонаже.
     * @param bookId Уникальный идентификатор книги.
     * @param characterId Уникальный идентификатор персонажа.
     * @return Result с полной информацией о персонаже.
     */
    suspend fun getCharacterDetails(bookId: String, characterId: String): Result<BookCharacter>

    /**
     * Получает информацию о главе (тизер/синопсис).
     * @param bookId Уникальный идентификатор книги.
     * @param chapterId Уникальный идентификатор главы.
     * @return Result с информацией о главе.
     */
    suspend fun getChapterSummary(bookId: String, chapterId: String): Result<ChapterSummary>

    /**
     * Загружает архив книги (.bw) по URL, распаковывает и сохраняет локально.
     * @param url Ссылка на .bw файл.
     * @return Flow с прогрессом загрузки и установки.
     */
    fun downloadAndInstallBook(url: String): Flow<DownloadProgress>

    /**
     * Устанавливает книгу из потока данных.
     * @param inputStream Поток данных .bw архива.
     * @return Result с путем к папке книги в случае успеха.
     */
    suspend fun installBook(inputStream: InputStream): Result<File>

    /**
     * Получает оригинальный текст главы.
     * @param bookId Уникальный идентификатор книги.
     * @param chapterId Уникальный идентификатор главы.
     * @return Result с оригинальным текстом главы.
     */
    suspend fun getChapterOriginalText(bookId: String, chapterId: String): Result<String>

    /**
     * Удаляет все файлы, связанные с книгой, с устройства.
     * @param bookId Уникальный идентификатор книги для удаления.
     * @return Result.success(Unit) в случае успеха или Result.failure(Exception) при ошибке.
     */
    suspend fun deleteBook(bookId: String): Result<Unit>

    /**
     * Распарсивает сценарий для конкретной главы.
     * @param bookId Уникальный идентификатор книги.
     * @param chapterId Уникальный идентификатор главы.
     * @return Result со списком сценариев главы.
     */
    suspend fun getScenarioForChapter(
        bookId: String,
        chapterId: String
    ): Result<List<ScenarioEntry>>

    /**
     * Получает Flow с ID активной книги.
     * @return Flow с ID активной книги (или null, если нет активной).
     */
    fun getActiveBookIdFlow(): Flow<String?>

    /**
     * Устанавливает ID активной книги.
     * @param bookId ID книги, которую нужно сделать активной, или null для сброса.
     */
    suspend fun setActiveBookId(bookId: String?)

    /**
     * Получает ID активной книги один раз.
     * @return ID активной книги (или null, если нет активной).
     */
    suspend fun getActiveBookId(): String?

    /**
     * Получает полную информацию, необходимую для запуска плеера для конкретной главы.
     * @param bookId Уникальный идентификатор книги.
     * @param chapterId Уникальный идентификатор главы.
     * @return Result с информацией для плеера.
     */
    suspend fun getPlayerChapterInfo(bookId: String, chapterId: String): Result<PlayerChapterInfo>

    /**
     * Устанавливает ID активной главы.
     * @param chapterId ID главы, которую нужно сделать активной, или null для сброса.
     */
    suspend fun setActiveChapterId(chapterId: String?)

    /**
     * Получает Flow с ID активной главы.
     * @return Flow с ID активной главы (или null, если нет активной).
     */
    fun getActiveChapterIdFlow(): Flow<String?>

    /**
     * Получает ID активной главы один раз.
     * @return ID активной главы (или null, если нет активной).
     */
    suspend fun getActiveChapterId(): String?

    /**
     * Получает Flow с основным цветом темы книги.
     * @param bookId Уникальный идентификатор книги.
     * @return Flow с цветом темы книги (или null, если не установлен).
     */
    fun getBookThemeColorFlow(bookId: String): Flow<Int?>

    /**
     * Генерирует и кэширует цвет темы книги на основе обложки.
     * @param bookId Уникальный идентификатор книги.
     * @param coverPath Путь к обложке книги (локальный файл или URL).
     */
    suspend fun generateAndCacheThemeColor(bookId: String, coverPath: String?)

    /**
     * Сохраняет прогресс прослушивания для определенной главы.
     * @param bookId Уникальный идентификатор книги.
     * @param chapterId Уникальный идентификатор главы.
     * @param position Позиция прослушивания в миллисекундах.
     */
    suspend fun saveListenProgress(bookId: String, chapterId: String, position: Long)

    /**
     * Получает путь к файлу эмбиента по имени.
     * @param bookId Уникальный идентификатор книги.
     * @param ambientName Имя эмбиента (например, "forest_loop").
     * @return Result с путем к файлу эмбиента (локальный путь или URL) или null, если не найден.
     */
    suspend fun getAmbientTrackPath(
        bookId: String,
        ambientName: String
    ): Result<String?>

    /**
     * Загружает, парсит и объединяет `scenario.json` и `subtitles.json`.
     * Возвращает чистую доменную модель `PlaybackEntry`.
     * Вся "грязная" работа по парсингу и маппингу остается в `data` слое.
     *
     * @param bookId Уникальный идентификатор книги.
     * @param chapterId Уникальный идентификатор главы.
     * @return Result с парой: List<PlaybackEntry> и String (путь к директории с аудиофайлами).
     */
    suspend fun getPlaybackData(
        bookId: String,
        chapterId: String
    ): Result<Pair<List<PlaybackEntry>, String>>

    /**
     * Получает ID последней прослушанной главы для данной книги.
     * @param bookId Уникальный идентификатор книги.
     * @return ID последней прослушанной главы или null, если нет данных.
     */
    suspend fun getLastListenedChapterId(bookId: String): String?

    /**
     * Принудительно синхронизирует список книг с подключенным сервером.
     * @return Result.success(Unit) в случае успеха или Result.failure(Exception) при ошибке.
     */
    suspend fun syncLibraryWithRemote(): Result<Unit>

    /**
     * Скачивает ОДНУ главу с сервера и сохраняет ее локально.
     * @param bookId Уникальный идентификатор книги.
     * @param chapterId Уникальный идентификатор главы.
     * @return Flow с прогрессом загрузки главы.
     */
    fun downloadChapter(bookId: String, chapterId: String): Flow<DownloadProgress>
}
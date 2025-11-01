package com.lapcevichme.bookweaver.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object (DAO) для работы с таблицей книг.
 */
@Dao
interface BookDao {
    /**
     * Возвращает Flow, который автоматически эммитит новый список
     * при любом изменении в таблице "books".
     */
    @Query("SELECT * FROM books ORDER BY title ASC")
    fun getAllBooks(): Flow<List<BookEntity>>

    /**
     * Добавляет или обновляет книгу в базе.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: BookEntity)

    /**
     * Удаляет книгу из базы по ее ID.
     */
    @Query("DELETE FROM books WHERE id = :bookId")
    suspend fun deleteBook(bookId: String)

    /**
     * Возвращает Flow только с цветом темы для конкретной книги.
     */
    @Query("SELECT themeColor FROM books WHERE id = :bookId")
    fun getBookThemeColor(bookId: String): Flow<Int?>

    /**
     * Обновляет цвет темы для конкретной книги.
     */
    @Query("UPDATE books SET themeColor = :color WHERE id = :bookId")
    suspend fun updateThemeColor(bookId: String, color: Int)

    /**
     * Обновляет прогресс прослушивания для конкретной книги.
     */
    @Query("UPDATE books SET lastListenedChapterId = :chapterId, lastListenedPosition = :position WHERE id = :bookId")
    suspend fun updateListenProgress(bookId: String, chapterId: String, position: Long)

    /**
     * Возвращает одну книгу по ID.
     */
    @Query("SELECT * FROM books WHERE id = :bookId LIMIT 1")
    suspend fun getBookById(bookId: String): BookEntity?
}

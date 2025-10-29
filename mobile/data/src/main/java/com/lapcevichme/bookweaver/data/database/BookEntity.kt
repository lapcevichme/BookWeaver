package com.lapcevichme.bookweaver.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Сущность (таблица) Room, которая хранит метаданные книги.
 * Это наш "кэш", заменяющий сканирование файловой системы.
 */
@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey
    val id: String, // bookId, например "mark-twain_the-innocents-abroad"
    val title: String,
    val author: String?,
    val localPath: String, // Путь к папке книги: /data/.../files/books/bookId
    val coverPath: String? // Путь к обложке: /data/.../files/books/bookId/cover.jpg
)

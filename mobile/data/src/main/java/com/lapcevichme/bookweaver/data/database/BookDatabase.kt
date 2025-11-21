package com.lapcevichme.bookweaver.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.lapcevichme.bookweaver.data.database.entities.BookEntity
import com.lapcevichme.bookweaver.data.database.entities.ChapterEntity

/**
 * Класс базы данных Room.
 */
@Database(
    entities = [BookEntity::class, ChapterEntity::class],
    version = 5,
    exportSchema = false
)
@TypeConverters(DatabaseConverters::class)
abstract class BookDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
}
package com.lapcevichme.bookweaver.data.database

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Класс базы данных Room.
 */
@Database(
    entities = [BookEntity::class],
    version = 3,
    exportSchema = false
)
abstract class BookDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
}

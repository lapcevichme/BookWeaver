package com.lapcevichme.bookweaver.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Миграция с версии 1 на 2.
 * Добавляет новую колонку 'themeColor' в таблицу 'books'.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Добавляем новую колонку themeColor с типом INTEGER,
        // которая может быть NULL и по умолчанию равна NULL.
        db.execSQL("ALTER TABLE books ADD COLUMN themeColor INTEGER DEFAULT NULL")
    }
}

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

/**
 * Миграция с версии 2 на 3.
 * Добавляет колонки для хранения прогресса прослушивания.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Добавляем колонку для ID последней главы (TEXT, может быть NULL)
        db.execSQL("ALTER TABLE books ADD COLUMN lastListenedChapterId TEXT DEFAULT NULL")

        // Добавляем колонку для позиции (INTEGER, НЕ NULL, по умолчанию 0)
        db.execSQL("ALTER TABLE books ADD COLUMN lastListenedPosition INTEGER NOT NULL DEFAULT 0")
    }
}
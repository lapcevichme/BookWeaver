package com.lapcevichme.bookweaver.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Миграция с версии 1 на 2.
 * Добавляет новую колонку 'themeColor' в таблицу 'books'.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE books ADD COLUMN themeColor INTEGER DEFAULT NULL")
    }
}

/**
 * Миграция с версии 2 на 3.
 * Добавляет колонки для хранения прогресса прослушивания.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE books ADD COLUMN lastListenedChapterId TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE books ADD COLUMN lastListenedPosition INTEGER NOT NULL DEFAULT 0")
    }
}


/**
 * НОВАЯ МИГРАЦИЯ с 3 на 4.
 * Добавляет таблицу 'chapters' и новые колонки в 'books'.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. Пересоздаем таблицу books с новыми полями и nullable типами
        db.execSQL("ALTER TABLE books RENAME TO books_old")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS books (
                id TEXT NOT NULL PRIMARY KEY, 
                title TEXT NOT NULL, 
                author TEXT, 
                themeColor INTEGER, 
                lastListenedChapterId TEXT, 
                lastListenedPosition INTEGER NOT NULL DEFAULT 0, 
                source TEXT NOT NULL DEFAULT 'LOCAL_ONLY', 
                serverHost TEXT, 
                remoteCoverUrl TEXT, 
                remoteManifestVersion INTEGER, 
                localPath TEXT, 
                coverPath TEXT
            )
            """
        )

        // Копируем данные
        db.execSQL(
            """
            INSERT INTO books (id, title, author, localPath, coverPath, themeColor, lastListenedChapterId, lastListenedPosition) 
            SELECT id, title, author, localPath, coverPath, themeColor, lastListenedChapterId, lastListenedPosition 
            FROM books_old
            """
        )

        db.execSQL("DROP TABLE books_old")

        // 2. Создаем таблицу chapters
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS chapters (
                id TEXT NOT NULL PRIMARY KEY, 
                bookId TEXT NOT NULL, 
                title TEXT NOT NULL, 
                downloadState TEXT NOT NULL DEFAULT 'NOT_DOWNLOADED', 
                localAudioPath TEXT, 
                localScenarioPath TEXT, 
                localSubtitlesPath TEXT, 
                localOriginalTextPath TEXT, 
                chapterIndex INTEGER NOT NULL DEFAULT 0, 
                remoteVersion INTEGER NOT NULL DEFAULT 1, 
                FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """
        )

        db.execSQL("CREATE INDEX IF NOT EXISTS index_chapters_bookId ON chapters(bookId)")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE chapters ADD COLUMN hasAudio INTEGER NOT NULL DEFAULT 0")
    }
}

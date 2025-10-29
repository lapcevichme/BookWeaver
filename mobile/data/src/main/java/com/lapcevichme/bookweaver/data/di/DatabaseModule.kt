package com.lapcevichme.bookweaver.data.di

import android.content.Context
import androidx.room.Room
import com.lapcevichme.bookweaver.data.database.BookDao
import com.lapcevichme.bookweaver.data.database.BookDatabase
import com.lapcevichme.bookweaver.data.database.MIGRATION_1_2
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt-модуль для предоставления зависимостей базы данных (Database и DAO).
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideBookDatabase(@ApplicationContext context: Context): BookDatabase {
        return Room.databaseBuilder(
            context,
            BookDatabase::class.java,
            "bookweaver_db"
        )
            .addMigrations(MIGRATION_1_2)
            .build()
    }

    @Provides
    @Singleton
    fun provideBookDao(database: BookDatabase): BookDao {
        return database.bookDao()
    }
}

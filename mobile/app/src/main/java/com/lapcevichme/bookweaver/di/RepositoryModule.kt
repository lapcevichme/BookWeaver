package com.lapcevichme.bookweaver.di

import com.lapcevichme.bookweaver.data.ServerRepositoryImpl
import com.lapcevichme.bookweaver.data.repository.AudioRepositoryImpl
import com.lapcevichme.bookweaver.data.repository.BookRepositoryImpl
import com.lapcevichme.bookweaver.data.repository.ConnectionRepositoryImpl
import com.lapcevichme.bookweaver.data.repository.SettingsRepositoryImpl
import com.lapcevichme.bookweaver.data.repository.mock.MockBookRepository
import com.lapcevichme.bookweaver.domain.repository.AudioRepository
import com.lapcevichme.bookweaver.domain.repository.BookRepository
import com.lapcevichme.bookweaver.domain.repository.ConnectionRepository
import com.lapcevichme.bookweaver.domain.repository.ServerRepository
import com.lapcevichme.bookweaver.domain.repository.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private const val USE_MOCKS = true

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAudioRepository(impl: AudioRepositoryImpl): AudioRepository

    companion object {
        @Provides
        @Singleton
        fun provideBookRepository(
            mockRepo: MockBookRepository,
            realRepo: BookRepositoryImpl
        ): BookRepository {
            // В зависимости от флага возвращаем либо моковую, либо реальную реализацию
            return if (USE_MOCKS) {
                mockRepo
            } else {
                realRepo
            }
        }
    }

    @Binds
    @Singleton
    abstract fun bindConnectionRepository(impl: ConnectionRepositoryImpl): ConnectionRepository


    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindServerRepository(
        impl: ServerRepositoryImpl // Указываем класс из библиотеки
    ): ServerRepository // Связываем его с интерфейсом из domain

}
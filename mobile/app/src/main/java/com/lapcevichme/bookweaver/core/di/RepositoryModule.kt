package com.lapcevichme.bookweaver.core.di

import com.lapcevichme.bookweaver.data.ServerRepositoryImpl
import com.lapcevichme.bookweaver.data.repository.BookRepositoryImpl
import com.lapcevichme.bookweaver.data.repository.ConnectionRepositoryImpl
import com.lapcevichme.bookweaver.data.repository.SettingsRepositoryImpl
import com.lapcevichme.bookweaver.data.repository.UserPreferencesRepositoryImpl
import com.lapcevichme.bookweaver.data.repository.mock.MockBookRepository
import com.lapcevichme.bookweaver.domain.repository.BookRepository
import com.lapcevichme.bookweaver.domain.repository.ConnectionRepository
import com.lapcevichme.bookweaver.domain.repository.ServerRepository
import com.lapcevichme.bookweaver.domain.repository.SettingsRepository
import com.lapcevichme.bookweaver.domain.repository.UserPreferencesRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private const val USE_MOCKS = false

@Module
@InstallIn(SingletonComponent::class)
object RepositoryProvidesModule {

    @Provides
    @Singleton
    fun provideBookRepository(
        mockRepo: MockBookRepository,
        realRepo: BookRepositoryImpl
    ): BookRepository {
        return if (USE_MOCKS) {
            mockRepo
        } else {
            realRepo
        }
    }
}


@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryBindsModule {
    @Binds
    @Singleton
    abstract fun bindConnectionRepository(impl: ConnectionRepositoryImpl): ConnectionRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindServerRepository(impl: ServerRepositoryImpl): ServerRepository

    @Binds
    @Singleton
    abstract fun bindUserPreferencesRepository(impl: UserPreferencesRepositoryImpl): UserPreferencesRepository
}

package com.lapcevichme.bookweaver.core.di

import android.app.Application
import android.content.Context
import com.lapcevichme.bookweaver.data.NsdHelper
import com.lapcevichme.bookweaver.data.WebSocketClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideWebSocketClient(application: Application): WebSocketClient {
        return WebSocketClient(application)
    }

    @Provides
    // NsdHelper не обязательно должен быть Singleton, Hilt создаст новый, когда понадобится
    fun provideNsdHelper(@ApplicationContext context: Context): NsdHelper {
        return NsdHelper(context)
    }
}
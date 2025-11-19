package com.lapcevichme.bookweaver.data.di

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.lapcevichme.bookweaver.domain.repository.ServerRepository
import com.lapcevichme.bookweaver.data.network.ApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideDynamicHostInterceptor(
        serverRepository: ServerRepository
    ): Interceptor {
        return Interceptor { chain ->
            val originalRequest = chain.request()
            val connection = serverRepository.getCurrentConnection()

            if (connection == null) {
                return@Interceptor chain.proceed(originalRequest)
            }

            val newUrl = connection.host.toHttpUrlOrNull()
            if (newUrl == null) {
                return@Interceptor chain.proceed(originalRequest)
            }

            val finalUrl = newUrl.newBuilder()
                .encodedPath(originalRequest.url.encodedPath)
                .encodedQuery(originalRequest.url.encodedQuery)
                .build()

            val newRequest: Request = originalRequest.newBuilder()
                .url(finalUrl)
                .header("Authorization", "Bearer ${connection.token}")
                .build()

            return@Interceptor chain.proceed(newRequest)
        }
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        dynamicHostInterceptor: Interceptor
    ): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        // РАСШИРЕНИЕ ПУЛА: Чтобы картинки и аудио не блокировали друг друга.
        val dispatcher = Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 30
        }

        return OkHttpClient.Builder()
            .dispatcher(dispatcher)
            // ЖИВУЧЕСТЬ СОЕДИНЕНИЙ: Держим сокеты открытыми дольше.
            .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
            .addInterceptor(dynamicHostInterceptor)
            .addInterceptor(loggingInterceptor)
            // ТАЙМАУТЫ: Даем серверу время на подумать (генерация ZIP).
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Provides
    @Singleton
    fun provideJson(): Json {
        return Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            encodeDefaults = true
        }
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient, json: Json): Retrofit {
        val contentType = "application/json".toMediaType()

        return Retrofit.Builder()
            .client(okHttpClient)
            .baseUrl("http://placeholder.bookweaver.com/")
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }

    @Provides
    @Singleton
    fun provideApiService(retrofit: Retrofit): ApiService {
        return retrofit.create(ApiService::class.java)
    }
}
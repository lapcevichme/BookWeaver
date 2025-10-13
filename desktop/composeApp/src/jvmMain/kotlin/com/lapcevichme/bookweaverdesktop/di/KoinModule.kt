package com.lapcevichme.bookweaverdesktop.di

import com.lapcevichme.bookweaverdesktop.backend.ApiClient
import com.lapcevichme.bookweaverdesktop.backend.BackendProcessManager
import com.lapcevichme.bookweaverdesktop.backend.BookManager
import com.lapcevichme.bookweaverdesktop.config.ConfigManager
import com.lapcevichme.bookweaverdesktop.server.ServerManager
import com.lapcevichme.bookweaverdesktop.settings.SettingsManager
import com.lapcevichme.bookweaverdesktop.ui.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module


val appModule = module {
    // --- СЕТЕВОЙ СЛОЙ И СЕРИАЛИЗАЦИЯ ---
    single {
        Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        }
    }
    single {
        HttpClient(CIO) {
            install(ContentNegotiation) { json(get()) }
        }
    }
    singleOf(::ApiClient)

    // --- УПРАВЛЕНИЕ БЭКЕНДОМ И ДАННЫМИ ---
    singleOf(::SettingsManager)
    singleOf(::ConfigManager)
    singleOf(::BookManager)
    singleOf(::BackendProcessManager)
    singleOf(::ServerManager)


    // --- VIEW MODELS ---
    factory { MainViewModel(get(), get()) }
    factory { DashboardViewModel(get(), get()) }
    factory { (bookName: String) -> WorkspaceViewModel(bookName, get(), get()) }
    factory { (bookName: String, volume: Int, chapter: Int) ->
        ScenarioEditorViewModel(bookName, volume, chapter, get(), get())
    }
    factory { SettingsAndAssetsViewModel(get(), get()) }
}

fun initKoin(appDeclaration: KoinAppDeclaration = {}) =
    org.koin.core.context.startKoin {
        appDeclaration()
        modules(appModule)
    }


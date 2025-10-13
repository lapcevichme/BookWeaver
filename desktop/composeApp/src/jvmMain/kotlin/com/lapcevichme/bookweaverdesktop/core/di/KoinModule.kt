package com.lapcevichme.bookweaverdesktop.core.di

import com.lapcevichme.bookweaverdesktop.data.backend.ApiClient
import com.lapcevichme.bookweaverdesktop.data.backend.BackendProcessManager
import com.lapcevichme.bookweaverdesktop.data.backend.BookManager
import com.lapcevichme.bookweaverdesktop.data.config.ConfigManager
import com.lapcevichme.bookweaverdesktop.server.ServerManager
import com.lapcevichme.bookweaverdesktop.core.settings.SettingsManager
import com.lapcevichme.bookweaverdesktop.ui.MainViewModel
import com.lapcevichme.bookweaverdesktop.ui.dashboard.DashboardViewModel
import com.lapcevichme.bookweaverdesktop.ui.editor.scenario.ScenarioEditorViewModel
import com.lapcevichme.bookweaverdesktop.ui.settings.SettingsAndAssetsViewModel
import com.lapcevichme.bookweaverdesktop.ui.workspace.WorkspaceViewModel
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import org.koin.core.context.startKoin
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
            classDiscriminator = "type"
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
    // ИЗМЕНЕНО: ServerManager теперь тоже зависит от SettingsManager
    single { ServerManager(get(), get(), get()) }


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
    startKoin {
        appDeclaration()
        modules(appModule)
    }


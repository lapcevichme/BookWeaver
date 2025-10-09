package com.lapcevichme.bookweaverdesktop.di

import com.lapcevichme.bookweaverdesktop.backend.ApiClient
import com.lapcevichme.bookweaverdesktop.backend.BackendProcessManager
import com.lapcevichme.bookweaverdesktop.config.ConfigManager
import com.lapcevichme.bookweaverdesktop.server.ServerManager
import com.lapcevichme.bookweaverdesktop.settings.SettingsManager
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

/**
 * Простой контейнер для управления зависимостями (DI).
 * Создает и хранит синглтоны наших сервисов.
 */
class AppContainer {
    private val settingsManager = SettingsManager()
    private val appSettings = settingsManager.loadSettings()

    // --- WebSocket Server ---
    val serverManager = ServerManager

    // --- AI Backend Components ---

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true // Позволяет обрабатывать менее строгий JSON
                ignoreUnknownKeys = true // Игнорирует поля в JSON, которых нет в data class
            })
        }
    }
    val apiClient = ApiClient(httpClient)

    val backendProcessManager = BackendProcessManager(settings = appSettings, apiClient = apiClient)

    val configManager = ConfigManager(settings = appSettings)
}


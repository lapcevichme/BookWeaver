package com.lapcevichme.bookweaverdesktop.core.settings

import kotlinx.serialization.Serializable

/**
 * Data-класс для хранения всех настроек приложения.
 * Использование @Serializable позволяет легко сохранять и загружать
 * настройки в формате JSON.
 */
@Serializable
data class AppSettings(
    val pythonExecutablePath: String,
    val backendWorkingDirectory: String
)

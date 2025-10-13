package com.lapcevichme.bookweaverdesktop.core.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

class SettingsManager(private val json: Json) {

    private val settingsFile: File by lazy {
        val appDataDir = getAppDataDir()
        File(appDataDir, "settings.json")
    }

    /**
     * Загружает настройки из файла settings.json.
     * Если файл не существует, создает его с настройками по умолчанию.
     * ИЗМЕНЕНО: Возвращает Result<AppSettings> для обработки ошибок.
     */
    suspend fun loadSettings(): Result<AppSettings> = withContext(Dispatchers.IO) {
        runCatching {
            if (!settingsFile.exists()) {
                logger.info { "Settings file not found, creating with defaults at ${settingsFile.absolutePath}" }
                val defaultSettings = createDefaultSettings()
                // При первом создании, сразу сохраняем файл. Если не получится, вся операция должна провалиться.
                saveSettings(defaultSettings).getOrThrow()
                defaultSettings
            } else {
                val content = settingsFile.readText()
                json.decodeFromString<AppSettings>(content)
            }
        }.onFailure {
            logger.error(it) { "Failed to load settings." }
        }
    }

    /**
     * Сохраняет предоставленные настройки в файл settings.json.
     * ИЗМЕНЕНО: Возвращает Result<Unit> для обработки ошибок.
     */
    suspend fun saveSettings(settings: AppSettings): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val content = json.encodeToString(settings)
            settingsFile.parentFile.mkdirs()
            settingsFile.writeText(content)
        }.onFailure {
            logger.error(it) { "Failed to save settings." }
        }
    }

    private fun createDefaultSettings(): AppSettings {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val pythonExecutable = if (isWindows) "python.exe" else "python3"
        return AppSettings(
            pythonExecutablePath = pythonExecutable,
            backendWorkingDirectory = ""
        )
    }

    private fun getAppDataDir(): File {
        val os = System.getProperty("os.name").uppercase()
        val userHome = System.getProperty("user.home")
        val path = when {
            os.contains("WIN") -> "$userHome/AppData/Roaming/BookWeaver"
            os.contains("MAC") -> "$userHome/Library/Application Support/BookWeaver"
            os.contains("NIX") || os.contains("NUX") || os.contains("AIX") -> "$userHome/.config/BookWeaver"
            else -> "$userHome/BookWeaver"
        }
        return File(path).apply { mkdirs() }
    }
}


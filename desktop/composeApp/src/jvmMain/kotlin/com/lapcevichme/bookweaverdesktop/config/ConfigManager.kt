package com.lapcevichme.bookweaverdesktop.config

import com.lapcevichme.bookweaverdesktop.settings.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ConfigManager(
    private val settingsManager: SettingsManager
) {
    /**
     * ИЗМЕНЕНО: Возвращает Result<File> для корректной обработки ошибок
     * при загрузке настроек из SettingsManager.
     */
    private suspend fun getConfigFile(): Result<File> {
        return settingsManager.loadSettings().map { settings ->
            File(settings.backendWorkingDirectory, "config.py")
        }
    }

    suspend fun loadConfigContent(): Result<String> = withContext(Dispatchers.IO) {
        // .fold - это безопасный способ развернуть Result
        getConfigFile().fold(
            onSuccess = { configFile ->
                runCatching {
                    // Проверка безопасности: убеждаемся, что файл находится внутри рабочей директории
                    val settings = settingsManager.loadSettings().getOrThrow()
                    val workingDir = File(settings.backendWorkingDirectory).canonicalFile
                    if (!configFile.canonicalFile.startsWith(workingDir)) {
                        throw SecurityException("Attempted to access file outside of the working directory.")
                    }

                    if (!configFile.exists()) {
                        "Файл конфигурации не найден: ${configFile.absolutePath}"
                    } else {
                        configFile.readText()
                    }
                }
            },
            onFailure = { error ->
                Result.failure(error)
            }
        )
    }

    suspend fun saveConfigContent(content: String): Result<Unit> = withContext(Dispatchers.IO) {
        getConfigFile().fold(
            onSuccess = { configFile ->
                runCatching {
                    val settings = settingsManager.loadSettings().getOrThrow()
                    val workingDir = File(settings.backendWorkingDirectory).canonicalFile
                    if (!configFile.canonicalFile.startsWith(workingDir)) {
                        throw SecurityException("Attempted to access file outside of the working directory.")
                    }
                    configFile.writeText(content)
                }
            },
            onFailure = { error ->
                Result.failure(error)
            }
        )
    }
}


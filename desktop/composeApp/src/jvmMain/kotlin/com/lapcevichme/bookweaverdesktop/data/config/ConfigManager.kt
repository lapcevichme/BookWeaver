package com.lapcevichme.bookweaverdesktop.data.config

import com.lapcevichme.bookweaverdesktop.core.settings.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ConfigManager(
    private val settingsManager: SettingsManager
) {
    /**
     * Внутренняя функция для получения и валидации пути к файлу конфигурации.
     */
    private suspend fun getConfigFile(): Result<File> = withContext(Dispatchers.IO) {
        settingsManager.loadSettings().fold(
            onSuccess = { settings ->
                val backendPath = settings.backendWorkingDirectory
                if (backendPath.isBlank()) {
                    return@fold Result.failure(Exception("Путь к рабочей директории бэкенда не указан в настройках."))
                }

                val baseDir = File(backendPath)
                if (!baseDir.isDirectory) {
                    return@fold Result.failure(Exception("Указанная директория бэкенда не существует или не является папкой: ${baseDir.absolutePath}"))
                }

                Result.success(File(baseDir, "config.py"))
            },
            onFailure = {
                // Пробрасываем ошибку загрузки настроек
                Result.failure(it)
            }
        )
    }

    /**
     * Асинхронно читает содержимое файла config.py.
     * @return Result, содержащий либо содержимое файла, либо ошибку.
     */
    suspend fun loadConfigContent(): Result<String> = withContext(Dispatchers.IO) {
        getConfigFile().fold(
            onSuccess = { file ->
                if (!file.exists()) {
                    Result.failure(Exception("Файл 'config.py' не найден по пути: ${file.absolutePath}"))
                } else {
                    runCatching { file.readText() }
                }
            },
            onFailure = {
                Result.failure(it)
            }
        )
    }

    /**
     * Асинхронно сохраняет новое содержимое в файл config.py.
     * @param content Новое содержимое файла.
     * @return Result с Unit в случае успеха или ошибкой.
     */
    suspend fun saveConfigContent(content: String): Result<Unit> = withContext(Dispatchers.IO) {
        getConfigFile().fold(
            onSuccess = { file ->
                runCatching { file.writeText(content) }
            },
            onFailure = {
                Result.failure(it)
            }
        )
    }
}


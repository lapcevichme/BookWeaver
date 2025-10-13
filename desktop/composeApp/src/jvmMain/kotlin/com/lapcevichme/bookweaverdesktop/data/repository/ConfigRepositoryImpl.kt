package com.lapcevichme.bookweaverdesktop.data.repository

import com.lapcevichme.bookweaverdesktop.core.settings.SettingsManager
import com.lapcevichme.bookweaverdesktop.domain.repository.ConfigRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ConfigRepositoryImpl(
    private val settingsManager: SettingsManager
) : ConfigRepository {

    private suspend fun getConfigFile(): Result<File> = withContext(Dispatchers.IO) {
        settingsManager.loadSettings().fold(
            onSuccess = { settings ->
                val backendPath = settings.backendWorkingDirectory
                if (backendPath.isBlank()) {
                    return@fold Result.failure(Exception("Путь к рабочей директории бэкенда не указан в настройках."))
                }
                val baseDir = File(backendPath)
                if (!baseDir.isDirectory) {
                    return@fold Result.failure(Exception("Директория бэкенда не найдена: ${baseDir.absolutePath}"))
                }
                Result.success(File(baseDir, "config.py"))
            },
            onFailure = { Result.failure(it) }
        )
    }

    override suspend fun getConfigContent(): Result<String> = withContext(Dispatchers.IO) {
        getConfigFile().fold(
            onSuccess = { file ->
                if (!file.exists()) {
                    Result.failure(Exception("Файл 'config.py' не найден: ${file.absolutePath}"))
                } else {
                    runCatching { file.readText() }
                }
            },
            onFailure = { Result.failure(it) }
        )
    }

    override suspend fun saveConfigContent(content: String): Result<Unit> = withContext(Dispatchers.IO) {
        getConfigFile().fold(
            onSuccess = { file -> runCatching { file.writeText(content) } },
            onFailure = { Result.failure(it) }
        )
    }
}

package com.lapcevichme.bookweaverdesktop.config

import com.lapcevichme.bookweaverdesktop.model.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Сервис для управления файлами конфигурации бэкенда.
 * Позволяет асинхронно читать и записывать содержимое файла config.py.
 */
class ConfigManager(settings: AppSettings) {
    private val configFile = File(settings.configPath)

    /**
     * Асинхронно читает весь контент файла config.py.
     * @return Содержимое файла или сообщение об ошибке.
     */
    suspend fun loadConfigContent(): String = withContext(Dispatchers.IO) {
        if (!configFile.exists()) {
            return@withContext "❌ Файл конфигурации не найден по пути: ${configFile.absolutePath}"
        }
        try {
            configFile.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            "❌ Ошибка при чтении файла конфигурации: ${e.message}"
        }
    }

    /**
     * Асинхронно сохраняет новое содержимое в файл config.py.
     * @param content Новое содержимое файла.
     * @return true в случае успеха, false и лог ошибки в случае неудачи.
     */
    suspend fun saveConfigContent(content: String): Boolean = withContext(Dispatchers.IO) {
        if (!configFile.exists()) {
            println("ERROR: Config file not found at ${configFile.absolutePath}")
            return@withContext false
        }
        try {
            configFile.writeText(content, Charsets.UTF_8)
            true
        } catch (e: Exception) {
            println("ERROR saving config file: ${e.message}")
            e.printStackTrace()
            false
        }
    }
}

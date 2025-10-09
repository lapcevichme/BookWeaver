package com.lapcevichme.bookweaverdesktop.config

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Сервис для управления файлами конфигурации бэкенда.
 * Позволяет асинхронно читать и записывать содержимое файла config.py.
 */
class ConfigManager {
    // TODO : динамически делать это как-то
    private val configFilePath = "/home/lapcevichme/PycharmProjects/BookWeaver/config.py"
    private val configFile = File(configFilePath)

    /**
     * Асинхронно читает весь контент файла config.py.
     * @return Содержимое файла или сообщение об ошибке.
     */
    suspend fun loadConfigContent(): String = withContext(Dispatchers.IO) {
        if (!configFile.exists()) {
            return@withContext "❌ Файл конфигурации не найден по пути: $configFilePath"
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
            println("ERROR: Config file not found at $configFilePath")
            return@withContext false
        }
        try {
            configFile.writeText(content, Charsets.UTF_8)
            true
        } catch (e: Exception) {
            println("ERROR: Failed to write config file: ${e.message}")
            false
        }
    }
}

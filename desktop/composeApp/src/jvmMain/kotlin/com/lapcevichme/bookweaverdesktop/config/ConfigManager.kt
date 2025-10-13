package com.lapcevichme.bookweaverdesktop.config

import com.lapcevichme.bookweaverdesktop.settings.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ConfigManager(
    private val settingsManager: SettingsManager
) {
    /**
     * Внутренняя функция для получения пути к файлу конфигурации.
     * Загружает настройки асинхронно, чтобы не блокировать поток.
     */
    private suspend fun getConfigFile(): File {
        // Загружаем настройки прямо здесь, в момент, когда они понадобились
        val settings = settingsManager.loadSettings()
        // Собираем путь к config.py относительно рабочей директории бэкенда
        return File(settings.backendWorkingDirectory, "config.py")
    }

    /**
     * Асинхронно читает содержимое файла config.py.
     * @return Содержимое файла или сообщение об ошибке.
     */
    suspend fun loadConfigContent(): String = withContext(Dispatchers.IO) {
        try {
            val file = getConfigFile()
            if (!file.exists()) {
                "❌ Файл конфигурации не найден по пути: ${file.absolutePath}"
            } else {
                file.readText()
            }
        } catch (e: Exception) {
            "❌ Не удалось прочитать config.py: ${e.message}"
        }
    }

    /**
     * Асинхронно сохраняет новое содержимое в файл config.py.
     * @param content Новое содержимое файла.
     * @return true в случае успеха, false в случае неудачи.
     */
    suspend fun saveConfigContent(content: String): Boolean = withContext(Dispatchers.IO) {
        try {
            getConfigFile().writeText(content)
            true
        } catch (e: Exception) {
            println("ERROR saving config file: ${e.message}")
            false
        }
    }
}


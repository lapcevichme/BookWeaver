package com.lapcevichme.bookweaverdesktop.settings

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.*

/**
 * Управляет загрузкой и сохранением настроек приложения из файла `settings.properties`.
 */
class SettingsManager {
    private val settingsFile = File("settings.properties")
    private val properties = Properties()

    companion object {
        const val PYTHON_EXEC_PATH_KEY = "python.executable.path"
        const val BACKEND_DIR_PATH_KEY = "backend.working.directory"
    }

    /**
     * Загружает настройки из файла. Если файл не существует, создает его с
     * путями по умолчанию.
     * @return Загруженные настройки AppSettings.
     */
    fun loadSettings(): AppSettings {
        if (!settingsFile.exists()) {
            createDefaultSettings()
        }

        FileInputStream(settingsFile).use { properties.load(it) }

        val pythonPath = properties.getProperty(PYTHON_EXEC_PATH_KEY, "")
        val backendDir = properties.getProperty(BACKEND_DIR_PATH_KEY, "")

        // Проверяем, что пути не пустые. Если пользователь еще не настроил,
        // лучше сообщить об этом, чем падать с ошибкой.
        if (pythonPath.isBlank() || backendDir.isBlank()) {
            println("WARNING: Settings file is not configured. Please edit 'settings.properties'.")
        }

        return AppSettings(
            pythonExecutablePath = pythonPath,
            backendWorkingDirectory = backendDir
        )
    }

    /**
     * НОВОЕ: Сохраняет объект AppSettings в файл settings.properties.
     */
    fun saveSettings(settings: AppSettings) {
        val newProperties = Properties()
        newProperties.setProperty(PYTHON_EXEC_PATH_KEY, settings.pythonExecutablePath)
        newProperties.setProperty(BACKEND_DIR_PATH_KEY, settings.backendWorkingDirectory)

        FileOutputStream(settingsFile).use { output ->
            newProperties.store(output, "BookWeaver Application Settings")
        }
        println("Settings saved to: ${settingsFile.absolutePath}")
    }

    /**
     * Создает файл `settings.properties` с инструкциями и пустыми значениями.
     */
    private fun createDefaultSettings() {
        val defaultProperties = Properties()
        defaultProperties.setProperty(PYTHON_EXEC_PATH_KEY, "/path/to/your/python/executable")
        defaultProperties.setProperty(BACKEND_DIR_PATH_KEY, "/path/to/your/BookWeaver/project/directory")

        val comments = """
            # BookWeaver Application Settings
            # Please update these paths to match your local environment.
            #
            # Example for Linux/macOS:
            # python.executable.path=/home/user/projects/BookWeaver/.venv/bin/python
            # backend.working.directory=/home/user/projects/BookWeaver
            #
            # Example for Windows:
            # python.executable.path=C:\\Users\\user\\projects\\BookWeaver\\.venv\\Scripts\\python.exe
            # backend.working.directory=C:\\Users\\user\\projects\\BookWeaver
        """.trimIndent()

        FileOutputStream(settingsFile).use { output ->
            defaultProperties.store(output, comments)
        }
        println("Created default settings file at: ${settingsFile.absolutePath}")
    }
}

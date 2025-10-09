package com.lapcevichme.bookweaverdesktop.settings

import java.nio.file.Paths

data class AppSettings(
    val pythonExecutablePath: String,
    val backendWorkingDirectory: String
) {
    /**
     * Вычисляемое свойство для получения полного пути к файлу config.py.
     */
    val configPath: String
        get() = Paths.get(backendWorkingDirectory, "config.py").toString()
}
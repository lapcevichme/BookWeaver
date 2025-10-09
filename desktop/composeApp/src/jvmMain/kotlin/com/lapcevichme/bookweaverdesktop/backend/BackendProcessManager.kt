package com.lapcevichme.bookweaverdesktop.backend

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Paths

class BackendProcessManager {

    sealed class State {
        object STOPPED : State()
        object STARTING : State()
        object RUNNING : State()
        data class FAILED(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.STOPPED)
    val state = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    private var process: Process? = null

    // Используем CoroutineScope для управления фоновыми задачами
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var logJob: Job? = null

    suspend fun start() {
        if (state.value != State.STOPPED && state.value !is State.FAILED) return

        _state.value = State.STARTING
        addLog("Attempting to start Python backend server...")

        try {
            val pythonExecutable = "/home/lapcevichme/PycharmProjects/parsers/.venv/bin/python"

            // ПУТЬ к корневой папке вашего Python-проекта (где лежит api_server.py)
            val workingDirectoryPath = "/home/lapcevichme/PycharmProjects/BookWeaver"
            val apiServerFile = "api_server.py"

            val workingDirectory = File(workingDirectoryPath)
            val fullScriptPath = Paths.get(workingDirectoryPath, apiServerFile).toString()

            // Проверка на существование файлов перед запуском
            if (!File(pythonExecutable).exists()) {
                val errorMsg = "Python executable not found at: $pythonExecutable"
                addLog("❌ $errorMsg")
                _state.value = State.FAILED(errorMsg)
                return
            }
            if (!workingDirectory.isDirectory) {
                val errorMsg = "Working directory not found: $workingDirectoryPath"
                addLog("❌ $errorMsg")
                _state.value = State.FAILED(errorMsg)
                return
            }

            addLog("Executing command: $pythonExecutable $fullScriptPath")

            val command = listOf(pythonExecutable, fullScriptPath)

            val processBuilder = ProcessBuilder(command)
                .directory(workingDirectory)
                .redirectErrorStream(true)

            // Запуск процесса в потоке IO, без блокировки основного потока
            val newProcess = withContext(Dispatchers.IO) {
                processBuilder.start()
            }
            process = newProcess

            // Запуск чтения логов в отдельной корутине
            logJob = scope.launch { readLogs() }

            // Ожидание и проверка статуса запуска (без блокировки)
            delay(5000)
            if (process?.isAlive == true) {
                _state.value = State.RUNNING
                addLog("✅ Backend server is likely running.")
            } else {
                val exitCode = process?.exitValue()
                val errorMsg = "Failed to start backend. Check logs for details. Exit code: $exitCode"
                addLog("❌ $errorMsg")
                _state.value = State.FAILED(errorMsg)
                // Отмена чтения логов, если процесс завершился ошибкой
                logJob?.cancel()
            }

        } catch (e: Exception) {
            val errorMsg = "Exception during backend start: ${e.message}"
            addLog("❌ $errorMsg")
            _state.value = State.FAILED(errorMsg)
            e.printStackTrace()
        }
    }

    suspend fun stop() {
        logJob?.cancelAndJoin() // Ждем завершения чтения логов
        process?.let {
            withContext(Dispatchers.IO) {
                addLog("Stopping backend server...")
                it.destroyForcibly()
                // Ждем завершения процесса
                it.waitFor()
                addLog("Backend server stopped.")
            }
        }
        process = null
        _state.value = State.STOPPED
    }

    /**
     * Читает стандартный вывод процесса построчно и добавляет его в лог.
     * Вызывается только из корутины.
     */
    private suspend fun readLogs() {
        process?.inputStream?.let {
            val reader = BufferedReader(InputStreamReader(it))
            // Используем Dispatchers.IO для блокирующего чтения строк
            withContext(Dispatchers.IO) {
                reader.useLines { lines ->
                    lines.forEach { line ->
                        if (isActive) {
                            // suspend-функция для безопасного обновления UI
                            addLog(line)
                        }
                    }
                }
            }
            // Процесс завершился, уведомляем
            if (process?.isAlive == false) {
                addLog("--- Python process terminated (Exit code: ${process?.exitValue()}) ---")
            }
        }
    }

    /**
     * Обновляет StateFlow с логами в потоке UI (Main) без блокировки.
     */
    private suspend fun addLog(line: String) {
        // Переключаемся в главный поток, чтобы безопасно обновить StateFlow
        withContext(Dispatchers.Main) {
            _logs.value += line.trim()
        }
    }
}

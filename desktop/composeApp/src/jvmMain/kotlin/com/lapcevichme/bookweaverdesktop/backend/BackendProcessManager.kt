package com.lapcevichme.bookweaverdesktop.backend

import com.lapcevichme.bookweaverdesktop.model.AppSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class BackendProcessManager(private val settings: AppSettings) {

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
            // Используем пути из настроек вместо хардкода
            val pythonExecutable = settings.pythonExecutablePath
            val workingDirectoryPath = settings.backendWorkingDirectory
            val apiServerFile = "api_server.py"

            if (pythonExecutable.isBlank() || workingDirectoryPath.isBlank() || "path/to" in pythonExecutable) {
                val errorMsg = "Python executable or working directory is not configured. Please check settings.properties."
                addLog("❌ ERROR: $errorMsg")
                _state.value = State.FAILED(errorMsg)
                return
            }

            val workingDir = File(workingDirectoryPath)
            if (!workingDir.isDirectory) {
                val errorMsg = "Working directory does not exist or is not a directory: $workingDirectoryPath"
                addLog("❌ ERROR: $errorMsg")
                _state.value = State.FAILED(errorMsg)
                return
            }

            val processBuilder = ProcessBuilder(
                pythonExecutable,
                "-m",
                "uvicorn",
                "api_server:app",
                "--host", "127.0.0.1",
                "--port", "8000"
            )
            processBuilder.directory(workingDir)
            processBuilder.redirectErrorStream(true)

            process = processBuilder.start()
            addLog("--- Python process started (PID: ${process?.pid()}) ---")

            logJob = scope.launch {
                readLogs()
            }

            // TODO: Заменить на надежную проверку health-check эндпоинта
            delay(5000)
            if (process?.isAlive == true) {
                _state.value = State.RUNNING
                addLog("✅ Backend server is presumed to be RUNNING.")
            } else {
                val exitCode = process?.exitValue()
                val errorMsg = "Failed to start backend. Process terminated unexpectedly with exit code: $exitCode"
                addLog("❌ $errorMsg")
                _state.value = State.FAILED(errorMsg)
                logJob?.cancel()
            }
        } catch (e: Exception) {
            val errorMsg = "Exception during backend start: ${e.message}"
            addLog("❌ CRITICAL: $errorMsg")
            e.printStackTrace()
            _state.value = State.FAILED(errorMsg)
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

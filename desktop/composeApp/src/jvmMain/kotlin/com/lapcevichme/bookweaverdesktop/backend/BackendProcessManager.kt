package com.lapcevichme.bookweaverdesktop.backend

import com.lapcevichme.bookweaverdesktop.model.ServerStateEnum
import com.lapcevichme.bookweaverdesktop.settings.SettingsManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import mu.KotlinLogging
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

class BackendProcessManager(
    private val settingsManager: SettingsManager,
    private val apiClient: ApiClient
) {
    sealed class State {
        data object STOPPED : State()
        data object STARTING : State()
        data object RUNNING_INITIALIZING : State() // Процесс запущен, API грузит модели
        data object RUNNING_HEALTHY : State()
        data class FAILED(val reason: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.STOPPED)
    val state = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    private var process: Process? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var healthCheckJob: Job? = null

    suspend fun start() {
        if (state.value !is State.STOPPED && state.value !is State.FAILED) {
            logger.warn { "Backend start attempted while not in STOPPED or FAILED state." }
            return
        }

        _state.value = State.STARTING
        addLog("Attempting to start Python backend...")

        val settings = settingsManager.loadSettings()
        val pythonExecutable = File(settings.pythonExecutablePath)
        val workingDir = File(settings.backendWorkingDirectory)

        // Валидация путей
        if (!pythonExecutable.exists() || !pythonExecutable.canExecute()) {
            val errorMsg = "Python executable not found or not executable at: ${pythonExecutable.absolutePath}"
            addLog("❌ $errorMsg")
            _state.value = State.FAILED(errorMsg)
            return
        }
        if (!workingDir.exists() || !workingDir.isDirectory) {
            val errorMsg = "Backend working directory not found: ${workingDir.absolutePath}"
            addLog("❌ $errorMsg")
            _state.value = State.FAILED(errorMsg)
            return
        }

        try {
            val processBuilder = ProcessBuilder(
                pythonExecutable.absolutePath,
                "-u", // Unbuffered output
                "api_server.py"
            ).directory(workingDir)

            process = withContext(Dispatchers.IO) {
                processBuilder.start()
            }
            _state.value = State.RUNNING_INITIALIZING
            addLog("--- Python process started (PID: ${process?.pid()}). Waiting for AI models to load... ---")

            // Запускаем чтение логов и проверку здоровья в фоновых корутинах
            scope.launch { readLogs() }
            healthCheckJob = scope.launch { pollHealthStatus() }

        } catch (e: Exception) {
            val errorMsg = "Failed to start Python process: ${e.message}"
            addLog("❌ $errorMsg")
            _state.value = State.FAILED(errorMsg)
        }
    }

    suspend fun stop() {
        healthCheckJob?.cancel()
        scope.coroutineContext.cancelChildren()

        process?.let { proc ->
            addLog("Stopping backend server (PID: ${proc.pid()})...")
            withContext(Dispatchers.IO) {
                // Пытаемся завершить процесс штатно
                proc.destroy()
                val gracefullyExited = proc.waitFor(5, TimeUnit.SECONDS)

                if (gracefullyExited) {
                    addLog("✅ Process terminated gracefully with exit code: ${proc.exitValue()}.")
                } else {
                    // Если не получилось, завершаем принудительно
                    addLog("Graceful shutdown timed out. Forcing termination...")
                    proc.destroyForcibly()
                    proc.waitFor(5, TimeUnit.SECONDS)
                    addLog("✅ Process terminated forcibly.")
                }
            }
        }
        process = null
        _state.value = State.STOPPED
        addLog("Backend server stopped.")
    }

    private suspend fun pollHealthStatus() {
        val maxRetries = 30 // Ждем до 60 секунд
        for (attempt in 1..maxRetries) {
            if (!currentCoroutineContext().isActive) return

            apiClient.healthCheck()
                .onSuccess { serverStatus ->
                    when (serverStatus.status) {
                        ServerStateEnum.READY -> {
                            addLog("✅ Health check successful! Backend is online.")
                            _state.value = State.RUNNING_HEALTHY
                            return
                        }
                        ServerStateEnum.INITIALIZING -> {
                            addLog("... Health check attempt $attempt/$maxRetries: Backend is still initializing models.")
                        }
                        ServerStateEnum.ERROR -> {
                            val msg = "Backend reported an error during initialization: ${serverStatus.message}"
                            addLog("❌ $msg")
                            _state.value = State.FAILED(msg)
                            return
                        }
                    }
                }.onFailure {
                    addLog("... Health check attempt $attempt/$maxRetries failed to connect. Retrying...")
                }
            delay(2000)
        }
        val timeoutMsg = "API health check timed out after $maxRetries retries."
        addLog("❌ $timeoutMsg")
        _state.value = State.FAILED(timeoutMsg)
    }

    private suspend fun readLogs() {
        process?.inputStream?.let {
            val reader = BufferedReader(InputStreamReader(it))
            withContext(Dispatchers.IO) {
                reader.useLines { lines ->
                    lines.forEach { line ->
                        if (isActive) addLog(line)
                    }
                }
            }
            if (process?.isAlive == false && _state.value == State.RUNNING_HEALTHY) {
                addLog("--- Python process terminated unexpectedly (Exit code: ${process?.exitValue()}) ---")
                _state.value = State.FAILED("Process terminated unexpectedly")
            }
        }
    }

    private suspend fun addLog(line: String) {
        withContext(Dispatchers.Main) {
            _logs.value += line.trim()
        }
    }
}


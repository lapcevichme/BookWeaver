package com.lapcevichme.bookweaverdesktop.backend

import com.lapcevichme.bookweaverdesktop.settings.AppSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class BackendProcessManager(
    private val settings: AppSettings,
    private val apiClient: ApiClient // Инъекция ApiClient для health check
) {
    // Обновленные, более детальные состояния
    sealed class State {
        object STOPPED : State()
        object STARTING : State()
        object RUNNING_INITIALIZING : State() // Процесс запущен, API грузит модели
        object RUNNING_HEALTHY : State()
        data class FAILED(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.STOPPED)
    val state = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    private var process: Process? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var logJob: Job? = null
    private var healthCheckJob: Job? = null

    suspend fun start() {
        if (state.value !is State.STOPPED && state.value !is State.FAILED) return

        _state.value = State.STARTING
        addLog("Attempting to start Python backend server...")

        try {
            val processBuilder = ProcessBuilder(
                settings.pythonExecutablePath,
                "-u",
                "api_server.py"
            )
            processBuilder.directory(File(settings.backendWorkingDirectory))
            processBuilder.redirectErrorStream(true)

            process = processBuilder.start()
            _state.value = State.RUNNING_INITIALIZING // Сразу переходим в состояние инициализации
            addLog("--- Python process started (PID: ${process?.pid()}). Waiting for AI models to load... ---")

            logJob = scope.launch { readLogs() }
            healthCheckJob = scope.launch { pollHealthStatus() }

        } catch (e: Exception) {
            val errorMessage = "Failed to start backend process: ${e.message}"
            addLog("ERROR: $errorMessage")
            _state.value = State.FAILED(errorMessage)
        }
    }

    private suspend fun pollHealthStatus() {
        val maxRetries = 30 // Ждем до 60 секунд
        for (attempt in 1..maxRetries) {
            if (!currentCoroutineContext().isActive) return

            val result = apiClient.healthCheck()
            result.onSuccess { serverStatus ->
                when (serverStatus.status) {
                    "READY" -> {
                        addLog("✅ Health check successful! Backend is online.")
                        _state.value = State.RUNNING_HEALTHY
                        return // Успех, выходим
                    }

                    "INITIALIZING" -> {
                        addLog("... Health check attempt $attempt/$maxRetries: Backend is still initializing models. Waiting...")
                    }

                    "ERROR" -> {
                        val failureMessage =
                            "Backend reported a critical error during initialization: ${serverStatus.message}"
                        addLog("❌ ERROR: $failureMessage")
                        _state.value = State.FAILED(failureMessage)
                        return // Ошибка, выходим
                    }
                }
            }.onFailure {
                addLog("... Health check attempt $attempt/$maxRetries failed to connect. Retrying in 2s...")
            }
            delay(2000)
        }

        val failureMessage = "API health check timed out after $maxRetries retries."
        addLog("❌ ERROR: $failureMessage")
        _state.value = State.FAILED(failureMessage)
    }


    suspend fun stop() {
        healthCheckJob?.cancel()
        logJob?.cancel()
        scope.coroutineContext.cancelChildren()

        process?.let { proc ->
            val pid = proc.pid()
            addLog("Stopping backend server (PID: $pid)...")
            // --- ИЗМЕНЕНИЕ: Используем встроенные методы Process API ---
            withContext(Dispatchers.IO) {
                // 1. Попытка мягкого завершения (отправляет SIGTERM)
                addLog("Attempting graceful shutdown...")
                proc.destroy()
                val gracefullyExited = proc.waitFor(5, TimeUnit.SECONDS)

                if (gracefullyExited) {
                    addLog("✅ Process terminated gracefully with exit code: ${proc.exitValue()}.")
                } else {
                    // 2. Если не сработало, принудительное завершение (отправляет SIGKILL)
                    addLog("Graceful shutdown timed out. Forcing termination...")
                    proc.destroyForcibly()
                    val forciblyExited = proc.waitFor(5, TimeUnit.SECONDS)
                    if (forciblyExited) {
                        addLog("✅ Process terminated forcibly with exit code: ${proc.exitValue()}.")
                    } else {
                        addLog("❌ FAILED to terminate the process even with force.")
                    }
                }
            }
        }
        process = null
        _state.value = State.STOPPED
        addLog("Backend server stopped.")
    }


    private suspend fun readLogs() {
        process?.inputStream?.let {
            val reader = BufferedReader(InputStreamReader(it))
            withContext(Dispatchers.IO) {
                reader.useLines { lines ->
                    lines.forEach { line ->
                        if (isActive) {
                            addLog(line)
                        }
                    }
                }
            }
            if (process?.isAlive == false) {
                addLog("--- Python process terminated (Exit code: ${process?.exitValue()}) ---")
                // Если процесс умер, пока мы были здоровы, меняем статус
                if (_state.value == State.RUNNING_HEALTHY) {
                    _state.value = State.FAILED("Process terminated unexpectedly")
                }
            }
        }
    }

    private suspend fun addLog(line: String) {
        _logs.value += line.trim()

    }
}


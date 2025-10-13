package com.lapcevichme.bookweaverdesktop.backend

import com.lapcevichme.bookweaverdesktop.model.ServerState
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
    private var processJobs: Job? = null

    suspend fun start() {
        if (state.value !is State.STOPPED && state.value !is State.FAILED) {
            logger.warn { "Backend start attempted while not in STOPPED or FAILED state." }
            return
        }

        _state.value = State.STARTING
        _logs.value = emptyList()
        addLog("--- Starting Python backend process ---")

        // ИСПРАВЛЕНО: Безопасно получаем настройки или выходим с ошибкой
        val settings = settingsManager.loadSettings().getOrElse { error ->
            val errorMsg = "Failed to load settings: ${error.message}"
            addLog("❌ $errorMsg")
            _state.value = State.FAILED(errorMsg)
            return
        }

        val pythonExecutable = settings.pythonExecutablePath
        val workingDir = File(settings.backendWorkingDirectory)

        if (!workingDir.isDirectory) {
            val errorMsg = "Backend working directory not found: ${workingDir.absolutePath}"
            addLog("❌ $errorMsg")
            _state.value = State.FAILED(errorMsg)
            return
        }

        val processBuilder = ProcessBuilder(
            pythonExecutable, "main.py", "run-server"
        ).directory(workingDir).redirectErrorStream(false)

        try {
            process = processBuilder.start()
            addLog("✅ Python process started with PID: ${process?.pid()}")

            processJobs = scope.launch {
                launch { readStream(process?.inputStream, "[INFO]") }
                launch { readStream(process?.errorStream, "[ERROR]") }
                launch { waitForProcessExit() }
                launch { runHealthCheck() }
            }
        } catch (e: Exception) {
            val errorMsg = "Failed to start Python process: ${e.message}"
            addLog("❌ $errorMsg")
            logger.error(e) { errorMsg }
            _state.value = State.FAILED(errorMsg)
        }
    }

    suspend fun stop() {
        addLog("--- Stopping Python backend process ---")
        processJobs?.cancel()
        process?.let {
            it.destroyForcibly()
            it.waitFor(5, TimeUnit.SECONDS)
        }
        process = null
        _state.value = State.STOPPED
        addLog("--- Process stopped ---")
    }

    private suspend fun runHealthCheck() {
        val maxRetries = 15
        for (attempt in 1..maxRetries) {
            if (!currentCoroutineContext().isActive) return

            apiClient.healthCheck().onSuccess { serverStatus ->
                when (serverStatus.status) {
                    ServerState.READY -> {
                        addLog("✅ Health check successful. API is ready.")
                        _state.value = State.RUNNING_HEALTHY
                        return
                    }
                    ServerState.INITIALIZING -> {
                        _state.value = State.RUNNING_INITIALIZING
                        addLog("... API is initializing: ${serverStatus.message}")
                    }
                    ServerState.ERROR -> {
                        val msg = "Backend reported an error: ${serverStatus.message}"
                        addLog("❌ $msg")
                        _state.value = State.FAILED(msg)
                        return
                    }
                }
            }.onFailure {
                addLog("... Health check attempt $attempt/$maxRetries failed. Retrying...")
            }
            delay(2000)
        }
        val timeoutMsg = "API health check timed out after $maxRetries retries."
        addLog("❌ $timeoutMsg")
        _state.value = State.FAILED(timeoutMsg)
    }

    private suspend fun readStream(inputStream: java.io.InputStream?, prefix: String) {
        if (inputStream == null) return
        val reader = BufferedReader(InputStreamReader(inputStream))
        withContext(Dispatchers.IO) {
            reader.useLines { lines ->
                lines.forEach { line ->
                    if (isActive) addLog("$prefix $line")
                }
            }
        }
    }

    private suspend fun waitForProcessExit() {
        val exitCode = process?.waitFor()
        if (_state.value is State.RUNNING_HEALTHY || _state.value is State.RUNNING_INITIALIZING) {
            val errorMsg = "Process terminated unexpectedly with exit code: $exitCode"
            addLog("--- ❌ $errorMsg ---")
            _state.value = State.FAILED(errorMsg)
        }
    }

    private suspend fun addLog(line: String) {
        withContext(Dispatchers.Main) {
            _logs.value += line.trim()
        }
    }
}

package com.lapcevichme.bookweaverdesktop.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.bookweaverdesktop.backend.ApiClient
import com.lapcevichme.bookweaverdesktop.backend.BackendProcessManager
import com.lapcevichme.bookweaverdesktop.config.ConfigManager
import com.lapcevichme.bookweaverdesktop.model.ChapterTaskRequest
import com.lapcevichme.bookweaverdesktop.model.ServerState
import com.lapcevichme.bookweaverdesktop.model.TaskStatusResponse
import com.lapcevichme.bookweaverdesktop.server.ServerManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainViewModel(
    private val serverManager: ServerManager,
    private val backendProcessManager: BackendProcessManager,
    private val apiClient: ApiClient,
    private val configManager: ConfigManager
) : ViewModel() {

    // --- Состояния ---
    val webSocketServerState: StateFlow<ServerState> = serverManager.serverState
    val backendState: StateFlow<BackendProcessManager.State> = backendProcessManager.state
    val backendLogs: StateFlow<List<String>> = backendProcessManager.logs

    private val _taskStatus = MutableStateFlow(TaskStatusResponse.initial())
    val taskStatus: StateFlow<TaskStatusResponse> = _taskStatus.asStateFlow()

    private val _configContent = MutableStateFlow("Загрузка конфигурации...")
    val configContent: StateFlow<String> = _configContent.asStateFlow()

    private var pollingJob: Job? = null

    init {
        // Загружаем конфиг при инициализации ViewModel
        loadConfig()
    }

    // --- Управление WebSocket-сервером ---

    fun startWebSocketServer() {
        if (webSocketServerState.value is ServerState.Disconnected) {
            serverManager.start()
        }
    }

    fun showConnectionInstructions() {
        if (webSocketServerState.value is ServerState.ReadyForConnection) {
            serverManager.showConnectionInstructions()
        }
    }

    // --- Управление Python Backend Process (ВОССТАНОВЛЕНО) ---

    fun startBackend() {
        if (backendState.value == BackendProcessManager.State.STOPPED || backendState.value is BackendProcessManager.State.FAILED) {
            viewModelScope.launch {
                backendProcessManager.start()
            }
        }
    }

    fun stopBackend() {
        viewModelScope.launch {
            backendProcessManager.stop()
        }
    }

    // --- Управление файлом конфигурации (ВОССТАНОВЛЕНО) ---

    fun loadConfig() {
        viewModelScope.launch {
            _configContent.value = configManager.loadConfigContent()
        }
    }

    fun saveConfig(content: String) {
        viewModelScope.launch {
            val success = configManager.saveConfigContent(content)
            if (success) {
                _configContent.value = configManager.loadConfigContent()
                println("INFO: Config saved successfully.")
            } else {
                _configContent.value = "❌ ОШИБКА СОХРАНЕНИЯ: Проверьте права доступа и путь к файлу."
            }
        }
    }

    // --- Управление AI-задачами ---

    /**
     * Публичный метод для запуска задачи синтеза речи.
     */
    fun startTtsTask(bookName: String, volNum: Int, chapNum: Int) {
        val request = ChapterTaskRequest(bookName, volNum, chapNum)
        // Делегируем выполнение универсальному лаунчеру
        launchAndPollTask { apiClient.startTtsSynthesis(request) }
    }

    /**
     * Приватный универсальный метод для запуска любой задачи, которая требует опроса.
     */
    private fun launchAndPollTask(apiCall: suspend () -> Result<TaskStatusResponse>) {
        pollingJob?.cancel()
        _taskStatus.value = TaskStatusResponse.initial().copy(status = "processing", message = "Запуск задачи...")

        viewModelScope.launch {
            apiCall()
                .onSuccess { initialResponse ->
                    _taskStatus.value = initialResponse
                    startPollingStatus(initialResponse.taskId)
                }
                .onFailure { exception ->
                    _taskStatus.value = TaskStatusResponse(
                        taskId = "error",
                        status = "failed",
                        progress = 0.0,
                        message = "Ошибка запуска задачи: ${exception.message}"
                    )
                }
        }
    }

    private fun startPollingStatus(taskId: String) {
        pollingJob = viewModelScope.launch {
            while (isActive) {
                delay(2000)
                apiClient.getTaskStatus(taskId)
                    .onSuccess { status ->
                        _taskStatus.value = status
                        if (status.status == "complete" || status.status == "failed") break
                    }
                    .onFailure { exception ->
                        _taskStatus.value = _taskStatus.value.copy(
                            status = "failed",
                            message = "Потеряна связь с задачей: ${exception.message}"
                        )
                        break
                    }
            }
        }
    }

    // --- Жизненный цикл ---

    suspend fun onAppClose() {
        serverManager.stop()
        backendProcessManager.stop()
    }
}


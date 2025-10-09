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
import kotlinx.coroutines.launch

class MainViewModel(
    private val serverManager: ServerManager,
    private val backendProcessManager: BackendProcessManager,
    private val apiClient: ApiClient,
    private val configManager: ConfigManager
) : ViewModel() {

    // Состояние WebSocket-сервера для мобильного приложения
    val webSocketServerState: StateFlow<ServerState> = serverManager.serverState

    // Состояние Python-процесса
    val backendState: StateFlow<BackendProcessManager.State> = backendProcessManager.state
    val backendLogs: StateFlow<List<String>> = backendProcessManager.logs

    // Состояние текущей запущенной AI-задачи
    private val _taskStatus = MutableStateFlow(TaskStatusResponse.initial())
    val taskStatus: StateFlow<TaskStatusResponse> = _taskStatus.asStateFlow()

    // Состояние для содержимого конфиг-файла
    private val _configContent = MutableStateFlow("Загрузка конфигурации...")
    val configContent: StateFlow<String> = _configContent.asStateFlow()

    private var pollingJob: Job? = null

    init {
        loadConfig()
    }

    // --- Функции для ConfigManager ---

    fun loadConfig() {
        viewModelScope.launch {
            _configContent.value = configManager.loadConfigContent()
        }
    }

    fun saveConfig(content: String) {
        viewModelScope.launch {
            val success = configManager.saveConfigContent(content)
            if (success) {
                // После успешного сохранения, перезагружаем, чтобы убедиться, что все ок
                _configContent.value = configManager.loadConfigContent()
                println("INFO: Config saved successfully.")
            } else {
                // Если сохранение не удалось, возвращаем сообщение об ошибке
                _configContent.value = "❌ ОШИБКА СОХРАНЕНИЯ: Проверьте права доступа и путь к файлу."
            }
        }
    }

    fun startWebSocketServer() {
        if (webSocketServerState.value is ServerState.Disconnected) {
            serverManager.start()
        }
    }

    /**
     * Вызывает ServerManager для генерации данных подключения (QR-кода) и
     * переводит состояние в AwaitingConnection.
     */
    fun showConnectionInstructions() {
        // Мы вызываем эту функцию, только если сервер готов, но еще не ожидает подключения
        if (webSocketServerState.value is ServerState.ReadyForConnection) {
            serverManager.showConnectionInstructions()
        }
    }

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

    fun onAppClose() {
        // Эта функция вызывается при закрытии окна
        serverManager.stop()
        stopBackend()
    }

    fun startTtsTask(bookName: String, volNum: Int, chapNum: Int) {
        pollingJob?.cancel() // Отменяем предыдущее отслеживание, если оно было
        _taskStatus.value = TaskStatusResponse.initial()

        viewModelScope.launch {
            val request = ChapterTaskRequest(bookName, volNum, chapNum)
            val initialResponse = apiClient.startTtsSynthesis(request)

            if (initialResponse != null) {
                _taskStatus.value = initialResponse
                startPollingStatus(initialResponse.taskId)
            } else {
                _taskStatus.value = TaskStatusResponse(
                    taskId = "error",
                    status = "failed",
                    progress = 0.0,
                    message = "Не удалось запустить задачу."
                )
            }
        }
    }

    private fun startPollingStatus(taskId: String) {
        pollingJob = viewModelScope.launch {
            while (true) {
                val status = apiClient.getTaskStatus(taskId)
                if (status != null) {
                    _taskStatus.value = status
                    if (status.status == "complete" || status.status == "failed") {
                        break // Прекращаем опрос, если задача завершена
                    }
                } else {
                    _taskStatus.value = _taskStatus.value.copy(status = "failed", message = "Потеряна связь с задачей.")
                    break // Прекращаем, если не удалось получить статус
                }
                delay(2000)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        onAppClose()
    }
}

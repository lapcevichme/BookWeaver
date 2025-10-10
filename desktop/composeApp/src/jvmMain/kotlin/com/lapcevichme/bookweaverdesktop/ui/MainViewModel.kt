package com.lapcevichme.bookweaverdesktop.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.bookweaverdesktop.backend.ApiClient
import com.lapcevichme.bookweaverdesktop.backend.BackendProcessManager
import com.lapcevichme.bookweaverdesktop.backend.BookManager
import com.lapcevichme.bookweaverdesktop.config.ConfigManager
import com.lapcevichme.bookweaverdesktop.model.ChapterTaskRequest
import com.lapcevichme.bookweaverdesktop.model.ServerState
import com.lapcevichme.bookweaverdesktop.model.TaskStatusResponse
import com.lapcevichme.bookweaverdesktop.server.ServerManager
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Data class for parsing server error responses
@Serializable
private data class ErrorDetail(val detail: String)

class MainViewModel(
    private val serverManager: ServerManager,
    private val backendProcessManager: BackendProcessManager,
    private val apiClient: ApiClient,
    private val configManager: ConfigManager,
    private val bookManager: BookManager
) : ViewModel() {

    // --- Состояния ---
    private val _projects = MutableStateFlow<List<String>>(emptyList())
    val projects: StateFlow<List<String>> = _projects.asStateFlow()

    val webSocketServerState: StateFlow<ServerState> = serverManager.serverState
    val backendState: StateFlow<BackendProcessManager.State> = backendProcessManager.state
    val backendLogs: StateFlow<List<String>> = backendProcessManager.logs

    private val _taskStatus = MutableStateFlow(TaskStatusResponse.initial())
    val taskStatus: StateFlow<TaskStatusResponse> = _taskStatus.asStateFlow()

    private val _configContent = MutableStateFlow("Загрузка конфигурации...")
    val configContent: StateFlow<String> = _configContent.asStateFlow()

    private val _uiMessages = MutableSharedFlow<String>()
    val uiMessages: SharedFlow<String> = _uiMessages.asSharedFlow()


    private var pollingJob: Job? = null

    init {
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

    // --- Управление Python Backend Process ---

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

    // --- Управление Проектами (Книгами) ---

    fun loadProjects() {
        viewModelScope.launch {
            bookManager.getProjectList()
                .onSuccess { projectList -> _projects.value = projectList }
                .onFailure { e -> _uiMessages.emit("Ошибка загрузки списка проектов: ${e.message}") }
        }
    }

    fun importNewBook() {
        viewModelScope.launch {
            val fileToUpload = bookManager.selectBookFile() ?: return@launch
            _uiMessages.emit("Импорт файла ${fileToUpload.name}...")

            bookManager.importBook(fileToUpload)
                .onSuccess { response ->
                    if (response.status.isSuccess()) {
                        _uiMessages.emit("✅ Файл успешно импортирован!")
                        loadProjects() // Refresh the project list
                    } else {
                        // FIX: Use kotlinx.serialization to parse the error message
                        val errorBody = response.bodyAsText()
                        val errorMessage = try {
                            Json.decodeFromString<ErrorDetail>(errorBody).detail
                        } catch (e: Exception) {
                            errorBody.ifBlank { "HTTP ${response.status.value}" }
                        }
                        _uiMessages.emit("❌ Ошибка импорта: $errorMessage")
                    }
                }
                .onFailure { e ->
                    _uiMessages.emit("❌ Ошибка импорта файла: ${e.message}")
                }
        }
    }


    // --- Управление файлом конфигурации ---

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
                _uiMessages.emit("Конфигурация сохранена. Перезапустите AI Backend.")
            } else {
                _configContent.value = "❌ ОШИБКА СОХРАНЕНИЯ: Проверьте права доступа и путь к файлу."
            }
        }
    }

    // --- Управление AI-задачами ---

    fun startTtsTask(bookName: String, volNum: Int, chapNum: Int) {
        val request = ChapterTaskRequest(bookName, volNum, chapNum)
        launchAndPollTask { apiClient.startTtsSynthesis(request) }
    }

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


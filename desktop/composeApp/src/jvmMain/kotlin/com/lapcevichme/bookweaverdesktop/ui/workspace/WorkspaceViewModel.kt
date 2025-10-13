package com.lapcevichme.bookweaverdesktop.ui.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.bookweaverdesktop.data.backend.ApiClient
import com.lapcevichme.bookweaverdesktop.data.model.BookArtifact
import com.lapcevichme.bookweaverdesktop.data.model.ChapterStatus
import com.lapcevichme.bookweaverdesktop.data.model.ChapterTaskRequest
import com.lapcevichme.bookweaverdesktop.data.model.TaskStatus
import com.lapcevichme.bookweaverdesktop.data.model.TaskStatusEnum
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.onSuccess

class WorkspaceViewModel(
    private val bookName: String,
    private val apiClient: ApiClient,
    private val json: Json
) : ViewModel() {

    private val _uiState = MutableStateFlow(WorkspaceUiState(bookName = bookName))
    val uiState = _uiState.asStateFlow()

    private val _userMessages = MutableSharedFlow<String>()
    val userMessages = _userMessages.asSharedFlow()

    private var pollingJob: Job? = null

    init {
        loadProjectDetails()
    }

    fun loadProjectDetails() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            apiClient.getProjectDetails(bookName)
                .onSuccess { details ->
                    _uiState.update {
                        val currentSelected = it.selectedChapter
                        val newSelected = details.chapters.find { ch ->
                            ch.volumeNum == currentSelected?.volumeNum && ch.chapterNum == currentSelected.chapterNum
                        } ?: details.chapters.firstOrNull()
                        it.copy(isLoading = false, projectDetails = details, selectedChapter = newSelected)
                    }
                }
                .onFailure { error -> _uiState.update { it.copy(isLoading = false, errorMessage = "Ошибка: ${error.message}") } }
        }
    }

    fun selectChapter(chapter: ChapterStatus) {
        _uiState.update { it.copy(selectedChapter = chapter) }
    }

    fun generateScenario(volume: Int, chapter: Int) {
        val request = ChapterTaskRequest(bookName, volume, chapter)
        runTask(volume, chapter, TaskType.SCENARIO) { apiClient.startScenarioGeneration(request) }
    }

    fun synthesizeAudio(volume: Int, chapter: Int) {
        val request = ChapterTaskRequest(bookName, volume, chapter)
        runTask(volume, chapter, TaskType.AUDIO) { apiClient.startTtsSynthesis(request) }
    }

    private fun runTask(volume: Int, chapter: Int, taskType: TaskType, apiCall: suspend () -> Result<TaskStatus>) {
        pollingJob?.cancel()
        viewModelScope.launch {
            _uiState.update { it.copy(activeTask = ProcessingTaskDetails(volume, chapter, "", taskType)) }
            apiCall()
                .onSuccess { initialResponse ->
                    _uiState.update {
                        it.copy(activeTask = it.activeTask?.copy(
                            taskId = initialResponse.taskId,
                            status = initialResponse.status.name.lowercase(),
                            progress = initialResponse.progress.toFloat(),
                            stage = initialResponse.stage,
                            message = initialResponse.message
                        )
                        )
                    }
                    startPollingStatus(initialResponse.taskId, taskType)
                }
                .onFailure { exception ->
                    val errorMsg = "Ошибка запуска задачи: ${exception.message}"
                    _uiState.update { it.copy(activeTask = null, errorMessage = errorMsg) }
                    _userMessages.emit(errorMsg)
                }
        }
    }

    private fun startPollingStatus(taskId: String, taskType: TaskType) {
        pollingJob = viewModelScope.launch {
            while (isActive) {
                delay(2000)
                apiClient.getTaskStatus(taskId)
                    .onSuccess { statusResponse ->
                        _uiState.update {
                            it.copy(
                                activeTask = it.activeTask?.copy(
                                    status = statusResponse.status.name.lowercase(),
                                    progress = statusResponse.progress.toFloat(),
                                    stage = statusResponse.stage,
                                    message = statusResponse.message
                                )
                            )
                        }
                        if (statusResponse.status == TaskStatusEnum.COMPLETE || statusResponse.status == TaskStatusEnum.FAILED) {
                            break
                        }
                    }
                    .onFailure { exception ->
                        val errorMsg = "Потеряна связь с задачей: ${exception.message}"
                        _userMessages.emit(errorMsg)
                        _uiState.update { it.copy(errorMessage = errorMsg) }
                        break
                    }
            }
            val finishedTask = _uiState.value.activeTask
            if (finishedTask?.status == TaskStatusEnum.COMPLETE.name.lowercase()) {
                _userMessages.emit("✅ Задача успешно завершена!")
                loadProjectDetails() // Перезагружаем все детали, чтобы получить актуальное состояние
            } else {
                _userMessages.emit("❌ Задача завершилась с ошибкой.")
            }
            _uiState.update { it.copy(activeTask = null) }
        }
    }

    fun loadManifest() {
        viewModelScope.launch {
            _uiState.update { it.copy(assets = it.assets.copy(isManifestLoading = true)) }
            apiClient.getBookArtifact(bookName, BookArtifact.MANIFEST)
                .onSuccess { jsonElement ->
                    val formattedJson = json.encodeToString(JsonElement.serializer(), jsonElement)
                    _uiState.update { it.copy(assets = it.assets.copy(isManifestLoading = false, manifestContent = formattedJson)) }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(assets = it.assets.copy(isManifestLoading = false, manifestContent = "Ошибка загрузки: ${error.message}")) }
                }
        }
    }

    fun saveManifest(content: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(assets = it.assets.copy(isManifestSaving = true)) }
            try {
                val jsonElement = json.decodeFromString(JsonElement.serializer(), content)
                if (jsonElement is JsonObject) {
                    apiClient.updateBookArtifact(bookName, BookArtifact.MANIFEST, jsonElement)
                        .onSuccess {
                            _userMessages.emit("✅ Манифест успешно сохранен!")
                            _uiState.update { it.copy(assets = it.assets.copy(isManifestSaving = false, manifestContent = content)) }
                        }
                        .onFailure { error ->
                            _userMessages.emit("❌ Ошибка сохранения: ${error.message}")
                            _uiState.update { it.copy(assets = it.assets.copy(isManifestSaving = false)) }
                        }
                } else {
                    _userMessages.emit("❌ Ошибка: Манифест должен быть JSON-объектом (начинаться с { ).")
                    _uiState.update { it.copy(assets = it.assets.copy(isManifestSaving = false)) }
                }
            } catch (e: Exception) {
                _userMessages.emit("❌ Ошибка: Введенный текст не является корректным JSON.")
                _uiState.update { it.copy(assets = it.assets.copy(isManifestSaving = false)) }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
    }
}


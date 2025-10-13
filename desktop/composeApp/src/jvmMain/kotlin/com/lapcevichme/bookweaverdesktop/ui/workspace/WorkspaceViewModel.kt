package com.lapcevichme.bookweaverdesktop.ui.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.bookweaverdesktop.domain.model.BookArtifact
import com.lapcevichme.bookweaverdesktop.domain.model.Chapter
import com.lapcevichme.bookweaverdesktop.domain.model.Task
import com.lapcevichme.bookweaverdesktop.domain.model.TaskStatus
import com.lapcevichme.bookweaverdesktop.domain.usecase.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

class WorkspaceViewModel(
    private val bookName: String,
    private val getProjectDetailsUseCase: GetProjectDetailsUseCase,
    private val getBookArtifactUseCase: GetBookArtifactUseCase,
    private val updateBookArtifactUseCase: UpdateBookArtifactUseCase,
    private val startScenarioGenerationUseCase: StartScenarioGenerationUseCase,
    private val startTtsSynthesisUseCase: StartTtsSynthesisUseCase,
    private val getTaskStatusUseCase: GetTaskStatusUseCase,
    private val json: Json // Оставляем для pretty-print
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
            getProjectDetailsUseCase(bookName)
                .onSuccess { details ->
                    _uiState.update {
                        val currentSelected = it.selectedChapter
                        val newSelected = details.chapters.find { ch ->
                            ch.volumeNumber == currentSelected?.volumeNumber && ch.chapterNumber == currentSelected.chapterNumber
                        } ?: details.chapters.firstOrNull()
                        it.copy(isLoading = false, projectDetails = details, selectedChapter = newSelected)
                    }
                }
                .onFailure { error -> _uiState.update { it.copy(isLoading = false, errorMessage = "Ошибка: ${error.message}") } }
        }
    }

    fun selectChapter(chapter: Chapter) {
        _uiState.update { it.copy(selectedChapter = chapter) }
    }

    fun generateScenario(volume: Int, chapter: Int) {
        runTask(volume, chapter, TaskType.SCENARIO) { startScenarioGenerationUseCase(bookName, volume, chapter) }
    }

    fun synthesizeAudio(volume: Int, chapter: Int) {
        runTask(volume, chapter, TaskType.AUDIO) { startTtsSynthesisUseCase(bookName, volume, chapter) }
    }

    private fun runTask(volume: Int, chapter: Int, taskType: TaskType, apiCall: suspend () -> Result<Task>) {
        pollingJob?.cancel()
        viewModelScope.launch {
            _uiState.update { it.copy(activeTask = null) }
            apiCall()
                .onSuccess { initialTask ->
                    // ИСПРАВЛЕНО: Создаем обертку ActiveTaskDetails
                    val taskDetails = ActiveTaskDetails(volume, chapter, taskType, initialTask)
                    _uiState.update { it.copy(activeTask = taskDetails) }
                    startPollingStatus(initialTask.id)
                }
                .onFailure { exception ->
                    val errorMsg = "Ошибка запуска задачи: ${exception.message}"
                    _uiState.update { it.copy(activeTask = null, errorMessage = errorMsg) }
                    _userMessages.emit(errorMsg)
                }
        }
    }

    private fun startPollingStatus(taskId: String) {
        pollingJob = viewModelScope.launch {
            while (isActive) {
                delay(2000)
                getTaskStatusUseCase(taskId)
                    .onSuccess { updatedTask ->
                        // ИСПРАВЛЕНО: Обновляем задачу внутри ActiveTaskDetails
                        _uiState.update { currentState ->
                            val updatedDetails = currentState.activeTask?.copy(task = updatedTask)
                            currentState.copy(activeTask = updatedDetails)
                        }
                        if (updatedTask.status == TaskStatus.COMPLETE || updatedTask.status == TaskStatus.FAILED) {
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
            // ИСПРАВЛЕНО: Достаем задачу из ActiveTaskDetails
            val finishedTask = _uiState.value.activeTask?.task
            if (finishedTask?.status == TaskStatus.COMPLETE) {
                _userMessages.emit("✅ Задача успешно завершена!")
                loadProjectDetails()
            } else {
                _userMessages.emit("❌ Задача завершилась с ошибкой: ${finishedTask?.message}")
            }
            _uiState.update { it.copy(activeTask = null) }
        }
    }

    fun loadManifest() {
        viewModelScope.launch {
            _uiState.update { it.copy(assets = it.assets.copy(isManifestLoading = true)) }
            getBookArtifactUseCase(bookName, BookArtifact.MANIFEST)
                .onSuccess { rawJson ->
                    val prettyJson = json.encodeToString(JsonElement.serializer(), json.parseToJsonElement(rawJson))
                    _uiState.update { it.copy(assets = it.assets.copy(isManifestLoading = false, manifestContent = prettyJson)) }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(assets = it.assets.copy(isManifestLoading = false, manifestContent = "Ошибка загрузки: ${error.message}")) }
                }
        }
    }

    fun saveManifest(content: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(assets = it.assets.copy(isManifestSaving = true)) }
            val validationResult = runCatching { json.parseToJsonElement(content) }
            if (validationResult.isFailure) {
                _userMessages.emit("❌ Ошибка: Введенный текст не является корректным JSON.")
                _uiState.update { it.copy(assets = it.assets.copy(isManifestSaving = false)) }
                return@launch
            }

            updateBookArtifactUseCase(bookName, BookArtifact.MANIFEST, content)
                .onSuccess {
                    _userMessages.emit("✅ Манифест успешно сохранен!")
                    _uiState.update { it.copy(assets = it.assets.copy(isManifestSaving = false, manifestContent = content)) }
                }
                .onFailure { error ->
                    _userMessages.emit("❌ Ошибка сохранения: ${error.message}")
                    _uiState.update { it.copy(assets = it.assets.copy(isManifestSaving = false)) }
                }
        }
    }

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
    }
}


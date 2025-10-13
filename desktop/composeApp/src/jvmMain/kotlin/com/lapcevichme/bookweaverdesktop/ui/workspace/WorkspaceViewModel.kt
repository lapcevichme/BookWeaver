package com.lapcevichme.bookweaverdesktop.ui.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

class WorkspaceViewModel(
    private val bookName: String,
    private val getProjectDetailsUseCase: GetProjectDetailsUseCase,
    private val startScenarioGenerationUseCase: StartScenarioGenerationUseCase,
    private val startTtsSynthesisUseCase: StartTtsSynthesisUseCase,
    private val startCharacterAnalysisUseCase: StartCharacterAnalysisUseCase,
    private val startSummaryGenerationUseCase: StartSummaryGenerationUseCase,
    private val startVoiceConversionUseCase: StartVoiceConversionUseCase,
    private val getTaskStatusUseCase: GetTaskStatusUseCase,
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

    // --- Методы запуска задач ---

    fun generateScenario(volume: Int, chapter: Int) {
        runChapterTask(volume, chapter, TaskType.SCENARIO) { startScenarioGenerationUseCase(bookName, volume, chapter) }
    }

    fun synthesizeAudio(volume: Int, chapter: Int) {
        runChapterTask(volume, chapter, TaskType.AUDIO) { startTtsSynthesisUseCase(bookName, volume, chapter) }
    }

    fun applyVoiceConversion(volume: Int, chapter: Int) {
        runChapterTask(volume, chapter, TaskType.VOICE_CONVERSION) { startVoiceConversionUseCase(bookName, volume, chapter) }
    }

    fun analyzeCharacters() {
        runBookTask(TaskType.CHARACTER_ANALYSIS) { startCharacterAnalysisUseCase(bookName) }
    }

    fun generateSummaries() {
        runBookTask(TaskType.SUMMARY_GENERATION) { startSummaryGenerationUseCase(bookName) }
    }

    // --- Приватная логика управления задачами ---

    private fun runChapterTask(volume: Int, chapter: Int, taskType: TaskType, apiCall: suspend () -> Result<Task>) {
        runTask(apiCall) { initialTask ->
            ActiveTaskDetails(taskType, initialTask, volume, chapter)
        }
    }

    private fun runBookTask(taskType: TaskType, apiCall: suspend () -> Result<Task>) {
        runTask(apiCall) { initialTask ->
            ActiveTaskDetails(taskType, initialTask)
        }
    }

    private fun runTask(
        apiCall: suspend () -> Result<Task>,
        taskDetailsBuilder: (Task) -> ActiveTaskDetails
    ) {
        pollingJob?.cancel()
        viewModelScope.launch {
            _uiState.update { it.copy(activeTask = null) }
            apiCall()
                .onSuccess { initialTask ->
                    val taskDetails = taskDetailsBuilder(initialTask)
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


    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
    }
}

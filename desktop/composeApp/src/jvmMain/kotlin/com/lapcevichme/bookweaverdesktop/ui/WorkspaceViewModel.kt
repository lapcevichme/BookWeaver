package com.lapcevichme.bookweaverdesktop.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.bookweaverdesktop.backend.ApiClient
import com.lapcevichme.bookweaverdesktop.model.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

enum class TaskType {
    SCENARIO, AUDIO
}

data class ProcessingTaskDetails(
    val volume: Int,
    val chapter: Int,
    val taskId: String,
    val taskType: TaskType,
    val status: String = "queued",
    val progress: Float = 0.0f,
    val message: String = "Задача в очереди..."
)

data class AssetsState(
    val manifestContent: String? = null,
    val isManifestLoading: Boolean = false,
    val isManifestSaving: Boolean = false,
    // TODO: Добавить состояние для персонажей
)

data class WorkspaceUiState(
    val bookName: String,
    val projectDetails: ProjectDetailsResponse? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val activeTask: ProcessingTaskDetails? = null,
    val selectedChapter: ChapterStatus? = null,
    val assets: AssetsState = AssetsState()
)

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

    // --- Управление проектом и задачами ---
    fun loadProjectDetails() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            apiClient.getProjectDetails(bookName)
                .onSuccess { details ->
                    _uiState.update {
                        val currentSelected = it.selectedChapter
                        val newSelected = if (currentSelected != null && details.chapters.any { c -> c.volumeNum == currentSelected.volumeNum && c.chapterNum == currentSelected.chapterNum }) {
                            details.chapters.find { ch ->
                                ch.volumeNum == currentSelected.volumeNum && ch.chapterNum == currentSelected.chapterNum
                            }
                        } else {
                            details.chapters.firstOrNull()
                        }
                        it.copy(
                            isLoading = false,
                            projectDetails = details,
                            selectedChapter = newSelected
                        )
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
        runTask(volume, chapter, TaskType.SCENARIO) {
            apiClient.startScenarioGeneration(request)
        }
    }

    fun synthesizeAudio(volume: Int, chapter: Int) {
        val request = ChapterTaskRequest(bookName, volume, chapter)
        runTask(volume, chapter, TaskType.AUDIO) {
            apiClient.startTtsSynthesis(request)
        }
    }

    private fun runTask(volume: Int, chapter: Int, taskType: TaskType, apiCall: suspend () -> Result<TaskStatusResponse>) {
        pollingJob?.cancel()
        viewModelScope.launch {
            _uiState.update { it.copy(activeTask = ProcessingTaskDetails(volume, chapter, "", taskType, "queued", 0.0f, "Запуск...")) }
            apiCall()
                .onSuccess { initialResponse ->
                    _uiState.update {
                        it.copy(
                            activeTask = ProcessingTaskDetails(
                                volume,
                                chapter,
                                initialResponse.taskId,
                                taskType,
                                initialResponse.status,
                                initialResponse.progress.toFloat(),
                                initialResponse.message
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
                                    status = statusResponse.status,
                                    progress = statusResponse.progress.toFloat(),
                                    message = statusResponse.message
                                )
                            )
                        }
                        if (statusResponse.status == "complete" || statusResponse.status == "failed") {
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
            if (finishedTask?.status == "complete") {
                updateChapterStateLocally(finishedTask.volume, finishedTask.chapter, taskType)
                _userMessages.emit("✅ Задача успешно завершена!")
            } else {
                _userMessages.emit("❌ Задача завершилась с ошибкой.")
            }
            _uiState.update { it.copy(activeTask = null) }
        }
    }

    private fun updateChapterStateLocally(volume: Int, chapterNum: Int, taskType: TaskType) {
        _uiState.update { currentState ->
            val updatedChapters = currentState.projectDetails?.chapters?.map { chapter ->
                if (chapter.volumeNum == volume && chapter.chapterNum == chapterNum) {
                    when (taskType) {
                        TaskType.SCENARIO -> chapter.copy(hasScenario = true)
                        TaskType.AUDIO -> chapter.copy(hasAudio = true)
                    }
                } else {
                    chapter
                }
            }
            val updatedSelectedChapter = updatedChapters?.find {
                it.volumeNum == currentState.selectedChapter?.volumeNum && it.chapterNum == currentState.selectedChapter.chapterNum
            }
            currentState.copy(
                projectDetails = currentState.projectDetails?.copy(chapters = updatedChapters ?: emptyList()),
                selectedChapter = updatedSelectedChapter
            )
        }
    }

    // --- Управление манифестом ---

    fun loadManifest() {
        viewModelScope.launch {
            _uiState.update { it.copy(assets = it.assets.copy(isManifestLoading = true)) }
            apiClient.getBookArtifact(bookName, BookArtifactName.MANIFEST)
                .onSuccess { jsonElement ->
                    val formattedJson = json.encodeToString(JsonElement.serializer(), jsonElement)
                    _uiState.update {
                        it.copy(assets = it.assets.copy(isManifestLoading = false, manifestContent = formattedJson))
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(assets = it.assets.copy(isManifestLoading = false, manifestContent = "Ошибка загрузки: ${error.message}"))
                    }
                }
        }
    }

    fun saveManifest(content: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(assets = it.assets.copy(isManifestSaving = true)) }
            try {
                val jsonElement = json.decodeFromString(JsonElement.serializer(), content)

                // Проверяем, что декодированный элемент - это именно JsonObject
                if (jsonElement is JsonObject) {
                    apiClient.updateBookArtifact(bookName, BookArtifactName.MANIFEST, jsonElement)
                        .onSuccess {
                            _userMessages.emit("✅ Манифест успешно сохранен!")
                            _uiState.update {
                                it.copy(assets = it.assets.copy(isManifestSaving = false, manifestContent = content))
                            }
                        }
                        .onFailure { error ->
                            _userMessages.emit("❌ Ошибка сохранения: ${error.message}")
                            _uiState.update { it.copy(assets = it.assets.copy(isManifestSaving = false)) }
                        }
                } else {
                    // Если тип не JsonObject, сообщаем об ошибке
                    _userMessages.emit("❌ Ошибка: Манифест должен быть JSON-объектом (начинаться с { ).")
                    _uiState.update { it.copy(assets = it.assets.copy(isManifestSaving = false)) }
                }
            } catch (e: Exception) {
                // Ошибка парсинга JSON
                _userMessages.emit("❌ Ошибка: Введенный текст не является корректным JSON.")
                _uiState.update { it.copy(assets = it.assets.copy(isManifestSaving = false)) }
            }
        }
    }

    // --- Жизненный цикл ---
    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
    }
}


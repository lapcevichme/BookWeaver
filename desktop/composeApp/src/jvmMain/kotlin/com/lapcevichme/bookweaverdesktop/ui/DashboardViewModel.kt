package com.lapcevichme.bookweaverdesktop.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.bookweaverdesktop.backend.ApiClient
import com.lapcevichme.bookweaverdesktop.backend.BookManager
import com.lapcevichme.bookweaverdesktop.model.ChapterStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProjectInfo(
    val name: String,
    val progress: Float, // от 0.0 до 1.0
    val status: String // Описание статуса, например "15/20 глав готово"
)

data class DashboardUiState(
    val projects: List<ProjectInfo> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
)

class DashboardViewModel(
    private val bookManager: BookManager,
    private val apiClient: ApiClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState = _uiState.asStateFlow()

    init {
        loadProjects()
    }

    fun loadProjects() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            bookManager.getProjectList()
                .onSuccess { projectNames ->
                    // Асинхронно запрашиваем детали для каждого проекта
                    val projectInfos = projectNames.map { name ->
                        async {
                            apiClient.getProjectDetails(name)
                                .map { details ->
                                    // Считаем прогресс на основе готовых глав
                                    val totalChapters = details.chapters.size
                                    if (totalChapters == 0) {
                                        return@map ProjectInfo(name, 0f, "Главы не найдены")
                                    }
                                    val completedChapters = details.chapters.count { it.isComplete() }
                                    val progress = completedChapters.toFloat() / totalChapters
                                    ProjectInfo(name, progress, "$completedChapters/$totalChapters глав готово")
                                }
                                .getOrDefault(ProjectInfo(name, 0f, "Ошибка загрузки деталей"))
                        }
                    }.awaitAll()

                    _uiState.update { it.copy(isLoading = false, projects = projectInfos) }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isLoading = false, errorMessage = "Ошибка загрузки проектов: ${error.message}")
                    }
                }
        }
    }

    private fun ChapterStatus.isComplete(): Boolean {
        return hasScenario && hasSubtitles && hasAudio
    }

    fun importNewBook() {
        viewModelScope.launch {
            val file = bookManager.selectBookFile() ?: return@launch
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            bookManager.importBook(file)
                .onSuccess {
                    loadProjects() // Перезагружаем список после импорта
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isLoading = false, errorMessage = "Ошибка импорта: ${error.message}")
                    }
                }
        }
    }
}


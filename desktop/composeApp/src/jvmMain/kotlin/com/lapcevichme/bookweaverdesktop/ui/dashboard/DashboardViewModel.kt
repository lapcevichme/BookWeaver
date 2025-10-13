package com.lapcevichme.bookweaverdesktop.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.bookweaverdesktop.data.backend.ApiClient
import com.lapcevichme.bookweaverdesktop.data.backend.BookManager
import com.lapcevichme.bookweaverdesktop.data.model.ChapterStatus
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class ProjectInfo(
    val name: String,
    val progress: Float, // от 0.0 до 1.0
    val status: String // Описание статуса, например "15/20 глав готово"
)

data class DashboardUiState(
    val projects: List<ProjectInfo> = emptyList(),
    val isLoading: Boolean = true,
    val isImporting: Boolean = false, // НОВОЕ СОСТОЯНИЕ для отслеживания импорта
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
            // Сбрасываем isImporting при обновлении списка
            _uiState.update { it.copy(isLoading = true, isImporting = false, errorMessage = null) }
            bookManager.getProjectList()
                .onSuccess { projectNames ->
                    val projectInfos = projectNames.map { name ->
                        async {
                            apiClient.getProjectDetails(name)
                                .map { details ->
                                    val totalChapters = details.chapters.size
                                    if (totalChapters == 0) {
                                        return@map ProjectInfo(name, 0f, "Главы не найдены")
                                    }
                                    val completedChapters = details.chapters.count { it.isComplete() }
                                    val progress = if (totalChapters > 0) completedChapters.toFloat() / totalChapters else 0f
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

    /**
     * ИЗМЕНЕНО: Теперь принимает File напрямую от UI.
     * Логика выбора файла перенесена на экран.
     */
    fun importNewBook(file: File) {
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true, errorMessage = null) }
            bookManager.importBook(file)
                .onSuccess { response ->
                    if (response.status.isSuccess()) {
                        // Успешно! Перезагружаем список проектов.
                        loadProjects() // Этот метод сам сбросит флаги isImporting и isLoading.
                    } else {
                        val errorMsg = "Ошибка импорта: Сервер вернул ошибку ${response.status.value}"
                        _uiState.update { it.copy(isImporting = false, errorMessage = errorMsg) }
                    }
                }
                .onFailure { error ->
                    // Сетевая или другая ошибка
                    _uiState.update {
                        it.copy(isImporting = false, errorMessage = "Ошибка импорта: ${error.message}")
                    }
                }
        }
    }
}

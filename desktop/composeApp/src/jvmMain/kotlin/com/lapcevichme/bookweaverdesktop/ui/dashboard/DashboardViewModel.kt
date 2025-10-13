package com.lapcevichme.bookweaverdesktop.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.bookweaverdesktop.domain.model.ProjectProgress
import com.lapcevichme.bookweaverdesktop.domain.usecase.GetProjectsProgressUseCase
import com.lapcevichme.bookweaverdesktop.domain.usecase.ImportBookUseCase
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
    val isImporting: Boolean = false,
    val errorMessage: String? = null,
)

class DashboardViewModel(
    private val getProjectsProgressUseCase: GetProjectsProgressUseCase,
    private val importBookUseCase: ImportBookUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState = _uiState.asStateFlow()

    init {
        loadProjects()
    }

    fun loadProjects() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, isImporting = false, errorMessage = null) }
            getProjectsProgressUseCase()
                .onSuccess { projectsProgress ->
                    val projectInfos = projectsProgress.map { it.toProjectInfo() }
                    _uiState.update { it.copy(isLoading = false, projects = projectInfos) }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isLoading = false, errorMessage = "Ошибка загрузки проектов: ${error.message}")
                    }
                }
        }
    }

    fun importNewBook(file: File) {
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true, errorMessage = null) }
            importBookUseCase(file)
                .onSuccess {
                    // Успешно! Перезагружаем список проектов.
                    loadProjects()
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isImporting = false, errorMessage = "Ошибка импорта: ${error.message}")
                    }
                }
        }
    }

    /**
     * Функция-маппер для преобразования доменной модели в UI-модель.
     */
    private fun ProjectProgress.toProjectInfo(): ProjectInfo {
        val progressFloat = if (totalChapters > 0) completedChapters.toFloat() / totalChapters else 0f
        val statusText = if (totalChapters > 0) "$completedChapters/$totalChapters глав готово" else "Главы не найдены"
        return ProjectInfo(name, progressFloat, statusText)
    }
}

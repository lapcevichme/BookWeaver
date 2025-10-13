package com.lapcevichme.bookweaverdesktop.ui.workspace

import com.lapcevichme.bookweaverdesktop.domain.model.Chapter
import com.lapcevichme.bookweaverdesktop.domain.model.ProjectDetails
import com.lapcevichme.bookweaverdesktop.domain.model.Task

enum class TaskType {
    CHARACTER_ANALYSIS, // Уровень книги
    SUMMARY_GENERATION, // Уровень книги
    SCENARIO,           // Уровень главы
    AUDIO,              // Уровень главы
    VOICE_CONVERSION    // Уровень главы
}

/**
 * UI-модель для хранения активной задачи и ее контекста.
 */
data class ActiveTaskDetails(
    val taskType: TaskType,
    val task: Task,
    val volumeNumber: Int? = null, // Nullable для задач уровня книги
    val chapterNumber: Int? = null // Nullable для задач уровня книги
)

data class AssetsState(
    val manifestContent: String? = null,
    val isManifestLoading: Boolean = false,
    val isManifestSaving: Boolean = false,
)

data class WorkspaceUiState(
    val bookName: String,
    val projectDetails: ProjectDetails? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val activeTask: ActiveTaskDetails? = null,
    val selectedChapter: Chapter? = null,
    val assets: AssetsState = AssetsState()
)

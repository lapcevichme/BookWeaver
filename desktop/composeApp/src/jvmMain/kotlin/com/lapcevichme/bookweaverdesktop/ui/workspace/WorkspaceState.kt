package com.lapcevichme.bookweaverdesktop.ui.workspace

import com.lapcevichme.bookweaverdesktop.domain.model.Chapter
import com.lapcevichme.bookweaverdesktop.domain.model.ProjectDetails
import com.lapcevichme.bookweaverdesktop.domain.model.Task

enum class TaskType {
    SCENARIO, AUDIO
}

/**
 * UI-модель для хранения активной задачи и ее контекста (к какой главе она относится).
 */
data class ActiveTaskDetails(
    val volumeNumber: Int,
    val chapterNumber: Int,
    val taskType: TaskType,
    val task: Task
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
    val activeTask: ActiveTaskDetails? = null, // Заменено с Task?
    val selectedChapter: Chapter? = null,
    val assets: AssetsState = AssetsState()
)


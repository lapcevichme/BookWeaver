package com.lapcevichme.bookweaverdesktop.ui.workspace

import com.lapcevichme.bookweaverdesktop.model.ChapterStatus
import com.lapcevichme.bookweaverdesktop.model.ProjectDetails

/**
 * Перечисление для идентификации типа фоновой задачи.
 */
enum class TaskType {
    SCENARIO, AUDIO
}

/**
 * Содержит всю информацию об активной задаче, выполняемой AI бэкендом.
 */
data class ProcessingTaskDetails(
    val volume: Int,
    val chapter: Int,
    val taskId: String,
    val taskType: TaskType,
    val status: String = "queued",
    val progress: Float = 0.0f,
    val stage: String = "",
    val message: String = "Задача в очереди..."
)

/**
 * Состояние для панели ассетов (манифест, персонажи).
 */
data class AssetsState(
    val manifestContent: String? = null,
    val isManifestLoading: Boolean = false,
    val isManifestSaving: Boolean = false,
)

/**
 * Полное состояние UI для экрана рабочего пространства проекта.
 */
data class WorkspaceUiState(
    val bookName: String,
    val projectDetails: ProjectDetails? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val activeTask: ProcessingTaskDetails? = null,
    val selectedChapter: ChapterStatus? = null,
    val assets: AssetsState = AssetsState()
)

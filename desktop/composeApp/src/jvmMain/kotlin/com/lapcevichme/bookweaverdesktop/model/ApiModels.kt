package com.lapcevichme.bookweaverdesktop.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Статус сервера. Соответствует ServerStatus в Python.
 */
@Serializable
data class ServerStatusResponse(
    val status: String, // "INITIALIZING", "READY", "ERROR"
    val message: String
)

/**
 * Запрос на запуск задачи для всей книги.
 */
@Serializable
data class BookTaskRequest(
    @SerialName("book_name") val bookName: String
)

/**
 * Запрос на запуск задачи для конкретной главы.
 */
@Serializable
data class ChapterTaskRequest(
    @SerialName("book_name") val bookName: String,
    @SerialName("volume_num") val volumeNum: Int,
    @SerialName("chapter_num") val chapterNum: Int
)

/**
 * Ответ со статусом запущенной задачи.
 */



/**
 * Статус обработки одной главы. Соответствует ChapterStatus в Python.
 */
@Serializable
data class ChapterStatus(
    @SerialName("volume_num") val volumeNum: Int,
    @SerialName("chapter_num") val chapterNum: Int,
    @SerialName("has_scenario") val hasScenario: Boolean,
    @SerialName("has_subtitles") val hasSubtitles: Boolean,
    @SerialName("has_audio") val hasAudio: Boolean
)

/**
 * Детальная информация о проекте (книге).
 */
@Serializable
data class ProjectDetailsResponse(
    @SerialName("book_name") val bookName: String,
    val chapters: List<ChapterStatus>
)

/**
 * Перечисление для артефактов уровня книги.
 */
enum class BookArtifactName(val fileName: String) {
    MANIFEST("manifest.json"),
    CHARACTER_ARCHIVE("character_archive.json"),
    SUMMARY_ARCHIVE("chapter_summaries.json")
}

/**
 * Перечисление для артефактов уровня главы.
 */
enum class ChapterArtifactName(val fileName: String) {
    SCENARIO("scenario.json"),
    SUBTITLES("subtitles.json")
}

@Serializable
data class TaskStatusResponse(
    @SerialName("task_id")
    val taskId: String,
    @SerialName("status")
    val status: TaskStatus,
    @SerialName("progress")
    val progress: Double,
    @SerialName("message")
    val message: String
) {
    companion object {
        fun initial() = TaskStatusResponse("", "idle", 0.0, "Нет активных задач.")
    }
}

typealias TaskStatus = String // "queued", "processing", "complete", "failed"

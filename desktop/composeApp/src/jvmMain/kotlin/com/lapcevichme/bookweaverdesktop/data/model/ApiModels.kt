package com.lapcevichme.bookweaverdesktop.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- Модели для Health Check и управления задачами ---

@Serializable
enum class ServerState {
    @SerialName("INITIALIZING")
    INITIALIZING,

    @SerialName("READY")
    READY,

    @SerialName("ERROR")
    ERROR
}

@Serializable
data class ServerStatus(
    @SerialName("status") val status: ServerState,
    @SerialName("message") val message: String? = null
)

@Serializable
enum class TaskStatusEnum {
    @SerialName("queued")
    QUEUED,

    @SerialName("processing")
    PROCESSING,

    @SerialName("complete")
    COMPLETE,

    @SerialName("failed")
    FAILED
}

/**
 * Имя модели изменено на TaskStatus для соответствия openapi (было TaskStatusResponse)
 */
@Serializable
data class TaskStatus(
    @SerialName("task_id") val taskId: String,
    @SerialName("status") val status: TaskStatusEnum,
    @SerialName("progress") val progress: Double,
    @SerialName("stage") val stage: String,
    @SerialName("message") val message: String
)

@Serializable
data class BookTaskRequest(
    @SerialName("book_name") val bookName: String
)

@Serializable
data class ChapterTaskRequest(
    @SerialName("book_name") val bookName: String,
    @SerialName("volume_num") val volumeNum: Int,
    @SerialName("chapter_num") val chapterNum: Int
)

// --- Модели для управления проектами ---

/**
 * Имя модели изменено на ProjectDetails для соответствия openapi (было ProjectDetailsResponse)
 */
@Serializable
data class ProjectDetails(
    @SerialName("book_name") val bookName: String,
    @SerialName("chapters") val chapters: List<ChapterStatus>
)

@Serializable
data class ChapterStatus(
    @SerialName("volume_num") val volumeNum: Int,
    @SerialName("chapter_num") val chapterNum: Int,
    @SerialName("has_scenario") val hasScenario: Boolean,
    @SerialName("has_subtitles") val hasSubtitles: Boolean,
    @SerialName("has_audio") val hasAudio: Boolean
)

// --- Модели и перечисления для артефактов ---

@Serializable
enum class BookArtifact {
    @SerialName("manifest")
    MANIFEST,

    @SerialName("character_archive")
    CHARACTER_ARCHIVE,

    @SerialName("summary_archive")
    SUMMARY_ARCHIVE
}

@Serializable
enum class ChapterArtifact {
    @SerialName("scenario")
    SCENARIO,

    @SerialName("subtitles")
    SUBTITLES
}

/**
 * Модель для одной реплики в сценарии.
 * Сервер возвращает список таких объектов.
 */
@Serializable
data class Replica(
    val speaker: String,
    val text: String
)

// --- Модели для обработки ошибок API ---

@Serializable
data class ApiError(
    @SerialName("detail") val detail: List<ValidationErrorDetail>
)

@Serializable
data class ValidationErrorDetail(
    @SerialName("loc") val location: List<String>,
    @SerialName("msg") val message: String,
    @SerialName("type") val type: String
)


package com.lapcevichme.bookweaverdesktop.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Этот файл содержит все data-классы, которые точно соответствуют
 * схеме OpenAPI вашего бэкенда. Использование @SerialName гарантирует,
 * что поля будут корректно парситься, даже если в Kotlin используется
 * camelCase, а в JSON - snake_case.
 */

// --- Модели для эндпоинтов управления задачами ---

@Serializable
data class TaskStatusResponse(
    @SerialName("task_id") val taskId: String,
    val status: String, // "queued", "processing", "complete", "failed"
    val progress: Double,
    val message: String
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


// --- Модели для эндпоинтов управления проектами ---

@Serializable
data class ProjectDetailsResponse(
    @SerialName("book_name") val bookName: String,
    val chapters: List<ChapterStatus>
)

@Serializable
data class ChapterStatus(
    @SerialName("volume_num") val volumeNum: Int,
    @SerialName("chapter_num") val chapterNum: Int,
    @SerialName("has_scenario") val hasScenario: Boolean,
    @SerialName("has_subtitles") val hasSubtitles: Boolean,
    @SerialName("has_audio") val hasAudio: Boolean
)


// --- Модели для артефактов ---

/**
 * ИСПРАВЛЕНИЕ: Это модель одной реплики в сценарии.
 * Сервер возвращает СПИСОК таких объектов, а не один объект Scenario.
 */
@Serializable
data class Replica(
    // ID нужен для стабильности списков в Compose UI, он не приходит с сервера
    val id: String,
    val speaker: String,
    val text: String
)

// --- Перечисления для имен артефактов ---

@Serializable
enum class BookArtifactName {
    @SerialName("manifest")
    MANIFEST,

    @SerialName("character_archive")
    CHARACTER_ARCHIVE,

    @SerialName("summary_archive")
    SUMMARY_ARCHIVE
}

@Serializable
enum class ChapterArtifactName {
    @SerialName("scenario")
    SCENARIO,

    @SerialName("subtitles")
    SUBTITLES
}

// --- Модели для Health Check ---

@Serializable
data class ServerStatus(
    val status: ServerStateEnum,
    val message: String = ""
)

@Serializable
enum class ServerStateEnum {
    @SerialName("INITIALIZING")
    INITIALIZING,

    @SerialName("READY")
    READY,

    @SerialName("ERROR")
    ERROR
}

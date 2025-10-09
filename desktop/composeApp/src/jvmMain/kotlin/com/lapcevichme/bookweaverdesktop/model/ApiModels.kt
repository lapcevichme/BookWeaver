package com.lapcevichme.bookweaverdesktop.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ServerStatusResponse(
    val status: String, // "INITIALIZING", "READY", "ERROR"
    val message: String
)

@Serializable
data class ChapterTaskRequest(
    @SerialName("book_name")
    val bookName: String,
    @SerialName("volume_num")
    val volumeNum: Int,
    @SerialName("chapter_num")
    val chapterNum: Int
)

@Serializable
data class BookTaskRequest(
    @SerialName("book_name")
    val bookName: String
)

typealias TaskStatus = String // "queued", "processing", "complete", "failed"

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

package com.lapcevichme.bookweaverdesktop.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    val bookName: String
)

typealias TaskStatus = String // "queued", "processing", "complete", "failed"

@Serializable
data class TaskStatusResponse(
    val taskId: String,
    val status: TaskStatus,
    val progress: Double,
    val message: String
) {
    companion object {
        fun initial() = TaskStatusResponse("", "idle", 0.0, "Нет активных задач.")
    }
}

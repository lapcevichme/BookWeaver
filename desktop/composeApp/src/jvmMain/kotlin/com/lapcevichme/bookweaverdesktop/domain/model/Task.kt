package com.lapcevichme.bookweaverdesktop.domain.model

/**
 * Доменная модель, описывающая состояние фоновой задачи (например, генерация AI).
 */
data class Task(
    val id: String,
    val status: TaskStatus,
    val progress: Double,
    val stage: String,
    val message: String
)

/**
 * Перечисление возможных статусов для фоновой задачи.
 */
enum class TaskStatus {
    QUEUED,
    PROCESSING,
    COMPLETE,
    FAILED
}

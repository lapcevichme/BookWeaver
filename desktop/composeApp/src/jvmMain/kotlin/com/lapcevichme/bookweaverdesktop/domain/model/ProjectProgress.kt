package com.lapcevichme.bookweaverdesktop.domain.model

/**
 * Доменная модель, описывающая прогресс по проекту.
 * @param name Название проекта.
 * @param completedChapters Количество завершенных глав.
 * @param totalChapters Общее количество глав.
 */
data class ProjectProgress(
    val name: String,
    val completedChapters: Int,
    val totalChapters: Int
)

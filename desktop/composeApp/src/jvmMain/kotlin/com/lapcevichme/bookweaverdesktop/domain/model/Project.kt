package com.lapcevichme.bookweaverdesktop.domain.model

/**
 * Базовая доменная модель проекта (книги).
 * Используется для отображения в списках.
 */
data class Project(
    val name: String
)

/**
 * Расширенная модель, содержащая детальную информацию о проекте,
 * включая список его глав.
 */
data class ProjectDetails(
    val name: String,
    val chapters: List<Chapter>
)

/**
 * Доменная модель, описывающая одну главу и статус ее артефактов.
 */
data class Chapter(
    val volumeNumber: Int,
    val chapterNumber: Int,
    val hasScenario: Boolean,
    val hasSubtitles: Boolean,
    val hasAudio: Boolean
)

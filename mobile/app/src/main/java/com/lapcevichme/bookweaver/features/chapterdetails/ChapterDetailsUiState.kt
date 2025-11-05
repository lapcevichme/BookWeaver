package com.lapcevichme.bookweaver.features.chapterdetails

/**
 * Модель UI-состояния экрана
 */
data class ChapterDetailsUiState(
    val isLoading: Boolean = false,
    val chapterTitle: String = "Загрузка...",
    val bookId: String = "",
    val chapterId: String = "",
    val details: UiChapterDetails? = null,
    val error: String? = null
)
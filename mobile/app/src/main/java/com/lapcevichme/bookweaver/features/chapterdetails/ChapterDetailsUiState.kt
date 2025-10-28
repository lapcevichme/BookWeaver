package com.lapcevichme.bookweaver.features.chapterdetails

data class ChapterDetailsUiState(
    val isLoading: Boolean = true,
    val chapterTitle: String = "Загрузка...",
    val details: UiChapterDetails? = null,
    val error: String? = null,
    val bookId: String = "",
    val chapterId: String = ""
)

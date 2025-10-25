package com.lapcevichme.bookweaver.features.bookhub

data class BookDetailsUiState(
    val isLoading: Boolean = true,
    val bookId: String? = null,
    val bookDetails: UiBookDetails? = null,
    val activeChapterId: String? = null,
    val error: String? = null
)
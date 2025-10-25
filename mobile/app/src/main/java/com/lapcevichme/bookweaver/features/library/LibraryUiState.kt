package com.lapcevichme.bookweaver.features.library

data class LibraryUiState(
    val isLoading: Boolean = true,
    val books: List<UiBook> = emptyList()
)
package com.lapcevichme.bookweaver.features.library

data class LibraryUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val books: List<UiBook> = emptyList(),
    val authToken: String? = null
)
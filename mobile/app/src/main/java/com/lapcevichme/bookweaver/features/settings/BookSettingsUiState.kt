package com.lapcevichme.bookweaver.features.settings

data class BookSettingsUiState(
    val bookTitle: String = "Загрузка...",
    val showDeleteConfirmDialog: Boolean = false,
    val deletionResult: Result<Unit>? = null
)
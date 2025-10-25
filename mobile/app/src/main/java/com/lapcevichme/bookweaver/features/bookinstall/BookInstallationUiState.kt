package com.lapcevichme.bookweaver.features.bookinstall

data class BookInstallationUiState(
    val isLoading: Boolean = false,
    val urlInput: String = "",
    val installationResult: Result<Unit>? = null
)
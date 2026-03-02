package com.lapcevichme.bookweaver.features.bookinstall

import com.lapcevichme.bookweaver.domain.model.DownloadProgress

data class BookInstallationUiState(
    val downloadProgress: DownloadProgress = DownloadProgress.Idle,
    val urlInput: String = "",
    val installationResult: Result<Unit>? = null
) {
    val isBusy: Boolean
        get() = downloadProgress !is DownloadProgress.Idle
}
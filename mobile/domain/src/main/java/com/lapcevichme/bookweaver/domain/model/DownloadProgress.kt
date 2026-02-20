package com.lapcevichme.bookweaver.domain.model

sealed class DownloadProgress {
    data object Idle : DownloadProgress()
    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long) : DownloadProgress()
    data object Installing : DownloadProgress()
}
package com.lapcevichme.bookweaver.domain.model

sealed class DownloadProgress {
    /** Начальное состояние / бездействие. */
    data object Idle : DownloadProgress()
    /** * Идет скачивание.
     * @param bytesDownloaded Сколько байт скачано.
     * @param totalBytes Общий размер файла в байтах.
     */
    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long) : DownloadProgress()
    /** Файл скачан, идет распаковка (установка). */
    data object Installing : DownloadProgress()
}
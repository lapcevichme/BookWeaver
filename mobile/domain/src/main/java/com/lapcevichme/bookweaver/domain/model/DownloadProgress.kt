package com.lapcevichme.bookweaver.domain.model

sealed class DownloadProgress {
    /** Начальное состояние / бездействие. */
    data object Idle : DownloadProgress()
    /** Идет скачивание. `percent` - значение от 0 до 100. */
    data class Downloading(val percent: Int) : DownloadProgress()
    /** Файл скачан, идет распаковка (установка). */
    data object Installing : DownloadProgress()
}
package com.lapcevichme.bookweaver.domain.model

enum class BookSource {
    /** Книга добавлена с сервера и синхронизируется. */
    SERVER,
    /** Книга добавлена вручную, "только локально". */
    LOCAL_ONLY
}

enum class DownloadState {
    NOT_DOWNLOADED,
    DOWNLOADING,
    DOWNLOADED,
    ERROR
}
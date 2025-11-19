package com.lapcevichme.bookweaver.domain.model

/**
 * Определяет источник книги в базе данных.
 */
enum class BookSource {
    /** Книга добавлена с сервера и синхронизируется. */
    SERVER,
    /** Книга добавлена вручную, "только локально". */
    LOCAL_ONLY
}

/**
 * Определяет статус загрузки главы.
 */
enum class DownloadState {
    NOT_DOWNLOADED,
    DOWNLOADING,
    DOWNLOADED,
    ERROR
}
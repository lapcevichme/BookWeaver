package com.lapcevichme.bookweaver.features.library

import com.lapcevichme.bookweaver.domain.model.Book
import com.lapcevichme.bookweaver.domain.model.BookSource

/**
 * Модель книги для UI-слоя.
 * Содержит поля, необходимые для отображения в списке.
 */
data class UiBook(
    val id: String,
    val title: String,
    val author: String,
    val coverPath: String?,
    val localPath: String?,
    val source: BookSource
)

/**
 * Маппер из доменной модели Book в UiBook.
 */
fun Book.toUiBook(): UiBook {
    return UiBook(
        id = this.id,
        title = this.title,
        author = this.author ?: "Неизвестный автор",
        coverPath = this.coverPath,
        localPath = this.localPath,
        source = this.source
    )
}
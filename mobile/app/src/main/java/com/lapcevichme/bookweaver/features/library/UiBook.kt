package com.lapcevichme.bookweaver.features.library

import com.lapcevichme.bookweaver.domain.model.Book

/**
 * UI-специфичная модель книги.
 * Содержит только те данные, которые нужны для отображения на экране библиотеки.
 */
data class UiBook(
    val id: String,
    val title: String,
    val author: String,
    val coverPath: String?
)

/**
 * Функция-маппер для преобразования domain-модели в UI-модель.
 */
fun Book.toUiBook(): UiBook {
    return UiBook(
        id = this.id,
        title = this.title,
        author = this.author ?: "Автор неизвестен",
        coverPath = this.coverPath
    )
}

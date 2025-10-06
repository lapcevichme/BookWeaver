package com.lapcevichme.bookweaver.domain.model

/**
 * UI-специфичная модель книги.
 * Это позволяет нам отделить логику отображения от сетевой логики.
 */
data class UiBook(
    val id: String, // Уникальный идентификатор для списка (мы используем filePath)
    val title: String,
    val author: String
    // Сюда в будущем можно будет добавить:
    // val downloadProgress: Int = 0,
    // val isCurrentlyPlaying: Boolean = false
)

/**
 * Функция-маппер для преобразования сетевой модели в UI-модель.
 */
fun Book.toUiBook(): UiBook {
    return UiBook(
        // В качестве ID для UI-списка временно используем путь к файлу
        id = this.filePath,
        title = this.title,
        author = this.author
        // Здесь можно будет добавлять другие поля, специфичные для UI
    )
}

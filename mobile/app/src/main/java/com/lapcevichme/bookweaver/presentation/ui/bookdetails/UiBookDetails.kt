package com.lapcevichme.bookweaver.presentation.ui.book_details.mapper

import com.lapcevichme.bookweaver.domain.model.BookDetails
import com.lapcevichme.bookweaver.domain.model.Chapter

/**
 * UI-модель для главы на экране деталей.
 */
data class UiChapter(
    val id: String,
    val title: String
)

/**
 * UI-модель для детальной информации о книге.
 */
data class UiBookDetails(
    val title: String,
    val chapters: List<UiChapter>
)

/**
 * Маппер из domain-модели BookDetails в UI-модель UiBookDetails.
 */
fun BookDetails.toUiBookDetails(): UiBookDetails {
    return UiBookDetails(
        title = this.manifest.bookName,
        chapters = this.chapters.map { it.toUiChapter() }
    )
}

/**
 * Маппер из domain-модели Chapter в UI-модель UiChapter.
 */
fun Chapter.toUiChapter(): UiChapter {
    return UiChapter(
        id = this.id,
        title = this.title
    )
}

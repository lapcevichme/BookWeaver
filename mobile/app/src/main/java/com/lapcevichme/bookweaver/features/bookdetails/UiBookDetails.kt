package com.lapcevichme.bookweaver.features.bookdetails

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
 * Новая UI-модель, представляющая один том со списком его глав.
 */
data class UiVolume(
    val title: String,
    val chapters: List<UiChapter>
)

/**
 * Обновленная UI-модель для детальной информации о книге.
 * Теперь содержит список томов вместо плоского списка глав.
 */
data class UiBookDetails(
    val title: String,
    val volumes: List<UiVolume>
)

/**
 * Маппер из domain-модели BookDetails в UI-модель UiBookDetails.
 * Теперь он выполняет группировку глав по томам.
 */
fun BookDetails.toUiBookDetails(): UiBookDetails {
    // Группируем главы по номеру тома, который извлекаем из ID главы
    val groupedByVolume = this.chapters
        .groupBy { it.id.substringBefore("_chap").substringAfter("vol_") }
        .map { (volumeNumber, chapters) ->
            UiVolume(
                title = "Том $volumeNumber",
                chapters = chapters.map { it.toUiChapter() }
            )
        }

    return UiBookDetails(
        title = this.manifest.bookName,
        volumes = groupedByVolume
    )
}

/**
 * Маппер из domain-модели Chapter в UI-модель UiChapter.
 */
fun Chapter.toUiChapter(): UiChapter {
    // Теперь в заголовок выносим только номер главы
    val chapterNumber = this.title.substringAfter("Глава ")
    return UiChapter(
        id = this.id,
        title = "Глава $chapterNumber"
    )
}

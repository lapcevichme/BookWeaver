package com.lapcevichme.bookweaver.features.bookhub

import com.lapcevichme.bookweaver.domain.model.BookDetails
import com.lapcevichme.bookweaver.domain.model.Chapter
import com.lapcevichme.bookweaver.domain.model.DownloadState

/**
 * Представление главы для UI-слоя.
 *
 * @param id Уникальный идентификатор главы.
 * @param title Название главы.
 * @param downloadState Состояние загрузки главы ([DownloadState]).
 */
data class UiChapter(
    val id: String,
    val title: String,
    val downloadState: DownloadState
)

/**
 * Представление тома книги, содержащего список глав, для UI-слоя.
 *
 * @param title Название тома (например, "Том 1").
 * @param chapters Список глав в этом томе.
 */
data class UiVolume(
    val title: String,
    val chapters: List<UiChapter>
)

/**
 * Представление детальной информации о книге для UI-слоя.
 *
 * @param title Название книги.
 * @param volumes Список томов в книге.
 */
data class UiBookDetails(
    val title: String,
    val volumes: List<UiVolume>
)

/**
 * Преобразует доменную модель [BookDetails] в UI-модель [UiBookDetails].
 *
 * Группирует главы по номерам томов. Если у главы не указан номер тома (`volumeNumber` is null),
 * она будет отнесена к первому тому.
 */
fun BookDetails.toUiBookDetails(): UiBookDetails {
    val groupedByVolume = this.chapters
        .groupBy { it.volumeNumber ?: 1 } // Главы без номера тома относятся к первому.
        .toSortedMap() // Сортируем тома по их номерам.
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
 * Преобразует доменную модель [Chapter] в UI-модель [UiChapter].
 */
fun Chapter.toUiChapter(): UiChapter {
    val cleanTitle = this.title.substringAfter("Глава ")
        .let { if (it.length < this.title.length) "Глава $it" else this.title }

    return UiChapter(
        id = this.id,
        title = cleanTitle,
        downloadState = this.downloadState
    )
}

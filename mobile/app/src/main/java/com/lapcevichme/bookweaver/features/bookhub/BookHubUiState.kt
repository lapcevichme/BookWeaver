package com.lapcevichme.bookweaver.features.bookhub

import com.lapcevichme.bookweaver.domain.model.BookDetails
import com.lapcevichme.bookweaver.domain.model.Chapter
import com.lapcevichme.bookweaver.domain.model.DownloadState

data class UiChapter(
    val id: String,
    val title: String,
    val downloadState: DownloadState,
    val hasAudio: Boolean
)

data class UiVolume(
    val title: String,
    val chapters: List<UiChapter>
)

data class UiBookDetails(
    val title: String,
    val volumes: List<UiVolume>
)

fun BookDetails.toUiBookDetails(): UiBookDetails {
    val groupedByVolume = this.chapters
        .groupBy { it.volumeNumber }
        .toSortedMap()
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

fun Chapter.toUiChapter(): UiChapter {
    val cleanTitle = this.title.substringAfter("Глава ")
        .let { if (it.length < this.title.length) "Глава $it" else this.title }

    return UiChapter(
        id = this.id,
        title = cleanTitle,
        downloadState = this.downloadState,
        hasAudio = this.hasAudio
    )
}

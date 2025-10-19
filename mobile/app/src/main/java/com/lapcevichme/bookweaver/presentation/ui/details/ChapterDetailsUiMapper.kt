package com.lapcevichme.bookweaver.presentation.ui.details

import com.lapcevichme.bookweaver.domain.model.ChapterDetails

data class UiScenarioEntry(
    val id: String,
    val speaker: String,
    val text: String
)

data class UiChapterDetails(
    val teaser: String,
    val synopsis: String,
    val scenario: List<UiScenarioEntry>,
    val originalText: String
)

fun ChapterDetails.toUiModel(): UiChapterDetails {
    return UiChapterDetails(
        teaser = this.summary?.teaser ?: "Тизер недоступен",
        synopsis = this.summary?.synopsis ?: "Синопсис недоступен",
        scenario = this.scenario.map {
            UiScenarioEntry(
                id = it.id.toString(),
                speaker = it.speaker,
                text = it.text
            )
        },
        originalText = this.originalText
    )
}
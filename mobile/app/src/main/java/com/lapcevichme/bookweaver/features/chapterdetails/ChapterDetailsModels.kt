package com.lapcevichme.bookweaver.features.chapterdetails

import com.lapcevichme.bookweaver.domain.model.ChapterDetails
import com.lapcevichme.bookweaver.domain.model.DomainWordEntry
import com.lapcevichme.bookweaver.domain.model.PlaybackEntry

data class UiScenarioEntry(
    val id: String,
    val speaker: String,
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val words: List<DomainWordEntry>,
    val ambient: String,
    val isPlayable: Boolean,
    val imageSrc: String? = null,
    val type: String = "narration"
)

data class UiChapterDetails(
    val teaser: String,
    val synopsis: String,
    val scenario: List<UiScenarioEntry>,
    val originalText: String,
    val hasAudio: Boolean,
    val basePath: String? = null
)


fun ChapterDetails.toUiModelTextOnly(): UiChapterDetails {
    val textOnlyScenario = this.scenario.map { scenarioEntry ->
        UiScenarioEntry(
            id = scenarioEntry.id.toString(),
            speaker = scenarioEntry.speaker,
            text = scenarioEntry.text,
            ambient = scenarioEntry.ambient,
            startMs = 0L,
            endMs = 0L,
            words = emptyList(),
            isPlayable = false,
            imageSrc = scenarioEntry.imageSrc,
            type = scenarioEntry.type
        )
    }

    return UiChapterDetails(
        teaser = this.summary?.teaser ?: "Тизер недоступен",
        synopsis = this.summary?.synopsis ?: "Синопсис недоступен",
        scenario = textOnlyScenario,
        originalText = this.originalText,
        hasAudio = false,
        basePath = this.dataPath
    )
}

fun ChapterDetails.toUiModelWithAudio(
    playbackData: List<PlaybackEntry>,
    audioPath: String? = null
): UiChapterDetails {
    val playbackMap = playbackData.associateBy { it.id }

    val interactiveScenario = this.scenario.map { scenarioEntry ->
        val playbackEntry = playbackMap[scenarioEntry.id.toString()]
        UiScenarioEntry(
            id = scenarioEntry.id.toString(),
            speaker = scenarioEntry.speaker,
            text = scenarioEntry.text,
            ambient = scenarioEntry.ambient,
            startMs = playbackEntry?.startMs ?: 0L,
            endMs = playbackEntry?.endMs ?: 0L,
            words = playbackEntry?.words ?: emptyList(),
            isPlayable = playbackEntry != null,
            imageSrc = scenarioEntry.imageSrc ?: playbackEntry?.imageSrc,
            type = scenarioEntry.type
        )
    }

    return UiChapterDetails(
        teaser = this.summary?.teaser ?: "Тизер недоступен",
        synopsis = this.summary?.synopsis ?: "Синопсис недоступен",
        scenario = interactiveScenario,
        originalText = this.originalText,
        hasAudio = true,
        basePath = audioPath ?: this.dataPath
    )
}

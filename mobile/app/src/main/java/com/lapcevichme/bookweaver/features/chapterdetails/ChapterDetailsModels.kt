package com.lapcevichme.bookweaver.features.chapterdetails

import com.lapcevichme.bookweaver.domain.model.ChapterDetails
import com.lapcevichme.bookweaver.domain.model.DomainWordEntry
import com.lapcevichme.bookweaver.domain.model.PlaybackEntry

/**
 * Модель одной реплики в UI.
 * Содержит все: и текст, и тайминги, и флаг 'isPlayable'.
 */
data class UiScenarioEntry(
    val id: String,
    val speaker: String,
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val words: List<DomainWordEntry>,
    val ambient: String,
    val isPlayable: Boolean
)

/**
 * Модель деталей, готовая для UI
 */
data class UiChapterDetails(
    val teaser: String,
    val synopsis: String,
    val scenario: List<UiScenarioEntry>,
    val originalText: String,
    val hasAudio: Boolean
)


/**
 * Маппер для "Этапа 1": Только текст из scenario.json
 * Создает UI-модель в режиме "только чтение".
 */
fun ChapterDetails.toUiModelTextOnly(): UiChapterDetails {
    val textOnlyScenario = this.scenario.map { scenarioEntry ->
        UiScenarioEntry(
            id = scenarioEntry.id.toString(),
            speaker = scenarioEntry.speaker,
            text = scenarioEntry.text,
            ambient = scenarioEntry.ambient,
            startMs = 0L, // Нет данных
            endMs = 0L, // Нет данных
            words = emptyList(), // Нет данных
            isPlayable = false
        )
    }

    return UiChapterDetails(
        teaser = this.summary?.teaser ?: "Тизер недоступен",
        synopsis = this.summary?.synopsis ?: "Синопсис недоступен",
        scenario = textOnlyScenario,
        originalText = this.originalText,
        hasAudio = false
    )
}

/**
 * Маппер для "Этапа 2": Обогащение данными из domain.PlaybackEntry
 * Создает UI-модель в интерактивном режиме.
 */
fun ChapterDetails.toUiModelWithAudio(
    playbackData: List<PlaybackEntry>
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
            isPlayable = playbackEntry != null
        )
    }

    return UiChapterDetails(
        teaser = this.summary?.teaser ?: "Тизер недоступен",
        synopsis = this.summary?.synopsis ?: "Синопсис недоступен",
        scenario = interactiveScenario,
        originalText = this.originalText,
        hasAudio = true
    )
}

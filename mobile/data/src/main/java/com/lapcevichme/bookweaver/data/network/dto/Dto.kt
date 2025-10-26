package com.lapcevichme.bookweaver.data.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Модификатор internal делает эти классы доступными в пределах всего модуля :data,
// но невидимыми для модуля :app.

@Serializable
internal data class BookManifestDto(
    @SerialName("book_name") val bookName: String,
    @SerialName("author") val author: String? = null,
    @SerialName("character_voices") val characterVoices: Map<String, String> = emptyMap(),
    @SerialName("default_narrator_voice") val defaultNarratorVoice: String
)

@Serializable
internal data class CharacterDto(
    val id: String,
    val name: String,
    val description: String,
    @SerialName("spoiler_free_description") val spoilerFreeDescription: String,
    val aliases: List<String> = emptyList(),
    @SerialName("chapter_mentions") val chapterMentions: Map<String, String> = emptyMap()
)

@Serializable
internal data class ChapterSummaryDto(
    @SerialName("chapter_id") val chapterId: String,
    val teaser: String,
    val synopsis: String
)

@Serializable
internal data class ScenarioEntryDto(
    val id: String,
    val type: String,
    val text: String,
    val speaker: String,
    val emotion: String? = null,
    val ambient: String = "none",
    @SerialName("audio_file") val audioFile: String? = null
)

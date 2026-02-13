package com.lapcevichme.bookweaver.data.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
@Serializable
data class BookStructureResponseDto(
    val manifest: BookManifestDto,
    val chapters: List<ChapterStructureDto>
)

@Serializable
data class ChapterStructureDto(
    val id: String,
    val title: String,
    @SerialName("version") val version: Int = 1,
    @SerialName("volume_number") val volumeNumber: Int? = null,
    @SerialName("has_audio") val hasAudio: Boolean = false
)

@Serializable
data class CharacterListItemDto(
    val id: String,
    val name: String,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("short_role") val shortRole: String? = null
)

@Serializable
data class ChapterInfoDto(
    @SerialName("chapter_id") val chapterId: String,
    val title: String,
    val teaser: String? = null,
    val synopsis: String? = null
)

@Serializable
data class BookMetaDto(
    val title: String,
    val author: String? = null,
    @SerialName("cover_image") val cover: String? = null,
    @SerialName("total_duration_ms") val totalDurationMs: Long = 0L
)

@Serializable
data class ChapterV2Dto(
    val id: String,
    val title: String,
    val path: String? = null
)

@Serializable
data class CharactersV2ResponseDto(
    val characters: List<CharacterDto>
)

@Serializable
data class BookManifestDto(
    val meta: BookMetaDto? = null,
    val structure: List<ChapterV2Dto> = emptyList(),
    @SerialName("book_name") val bookName: String = "", // Legacy
    @SerialName("author") val author: String? = null, // Legacy
    @SerialName("character_voices") val characterVoices: Map<String, String> = emptyMap(),
    @SerialName("default_narrator_voice") val defaultNarratorVoice: String? = null,
    @SerialName("version") val version: Int = 1,
    @SerialName("poster_url") val posterUrl: String? = null
)

@Serializable
data class CharacterDto(
    val id: String,
    val name: String,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val description: String = "",
    @SerialName("spoiler_free_description") val spoilerFreeDescription: String = "",
    val aliases: List<String> = emptyList(),
    @SerialName("chapter_mentions") val chapterMentions: Map<String, String> = emptyMap(),
    @SerialName("visual_base") val visualBase: String? = null,
    @SerialName("voice_base") val voiceBase: String? = null
)

@Serializable
data class ChapterSummaryDto(
    @SerialName("chapter_id") val chapterId: String,
    val teaser: String,
    val synopsis: String
)

@Serializable
data class ScenarioEntryDto(
    val id: String,
    val type: String,
    val text: String? = null,
    val speaker: String? = null,
    val emotion: String? = null,
    val ambient: String = "none",
    @SerialName("audio_file") val audioFile: String? = null,
    val src: String? = null
)

@Serializable
data class ScenarioContainerDto(
    val entries: List<ScenarioEntryDto>
)

@Serializable
data class PingResponseDto(
    val status: String,
    @SerialName("server_name") val serverName: String
)

@Serializable
data class PlaybackDataResponseDto(
    @SerialName("audio_url") val audioUrl: String? = null,
    @SerialName("duration_ms") val durationMs: Long,
    @SerialName("sync_map") val syncMap: List<PlaybackEntryDto> = emptyList(),
    val scenario: ScenarioContainerDto? = null,
    @SerialName("raw_text") val rawText: String? = null
)

@Serializable
data class PlaybackEntryDto(
    val id: String? = null,
    val text: String? = null,
    @SerialName("start_ms") val startMs: Long = 0L,
    @SerialName("end_ms") val endMs: Long = 0L,
    val words: List<DomainWordEntryDto> = emptyList(),
    val speaker: String? = null,
    val ambient: String = "none",
    val emotion: String? = null,
    val type: String = "narration",
    val sfx: String? = null,
    val src: String? = null
)

@Serializable
data class DomainWordEntryDto(
    val word: String,
    val start: Long,
    val end: Long
)
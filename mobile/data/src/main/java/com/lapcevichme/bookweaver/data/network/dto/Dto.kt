package com.lapcevichme.bookweaver.data.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- API: Structure & Metadata ---

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
    @SerialName("volume_number") val volumeNumber: Int? = null
)

// --- API: Characters ---

@Serializable
data class CharacterListItemDto(
    val id: String,
    val name: String,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("short_role") val shortRole: String? = null
)

// --- API: Chapters Info ---

@Serializable
data class ChapterInfoDto(
    @SerialName("chapter_id") val chapterId: String,
    val title: String,
    val teaser: String? = null,
    val synopsis: String? = null
)

// --- Shared DTOs ---

@Serializable
data class BookManifestDto(
    @SerialName("book_name") val bookName: String,
    @SerialName("author") val author: String? = null,
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
    val description: String,
    @SerialName("spoiler_free_description") val spoilerFreeDescription: String,
    val aliases: List<String> = emptyList(),
    @SerialName("chapter_mentions") val chapterMentions: Map<String, String> = emptyMap()
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
    val text: String,
    val speaker: String,
    val emotion: String? = null,
    val ambient: String = "none",
    @SerialName("audio_file") val audioFile: String? = null
)

@Serializable
data class PingResponseDto(
    val status: String,
    @SerialName("server_name") val serverName: String
)

// --- Playback Data ---

@Serializable
data class PlaybackDataResponseDto(
    val entries: List<PlaybackEntryDto>,
    @SerialName("audio_base_url") val audioBaseUrl: String
)

@Serializable
data class PlaybackEntryDto(
    val id: String,
    @SerialName("audio_file") val audioFile: String,
    val text: String,
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
    val words: List<DomainWordEntryDto>,
    val speaker: String,
    val ambient: String,
    val emotion: String?,
    val type: String
)

@Serializable
data class DomainWordEntryDto(
    val word: String,
    val start: Long,
    val end: Long
)
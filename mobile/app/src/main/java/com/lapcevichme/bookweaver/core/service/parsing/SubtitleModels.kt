package com.lapcevichme.bookweaver.core.service.parsing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WordEntry(
    val word: String,
    val start: Long,
    val end: Long
)

@Serializable
data class SubtitleEntry(
    @SerialName("audio_file") val audioFile: String,
    val text: String,
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
    val words: List<WordEntry> = emptyList()
)

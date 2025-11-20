package com.lapcevichme.bookweaver.data.network.mapper

import com.lapcevichme.bookweaver.data.network.dto.BookManifestDto
import com.lapcevichme.bookweaver.data.network.dto.ChapterSummaryDto
import com.lapcevichme.bookweaver.data.network.dto.CharacterDto
import com.lapcevichme.bookweaver.data.network.dto.DomainWordEntryDto
import com.lapcevichme.bookweaver.data.network.dto.PlaybackEntryDto
import com.lapcevichme.bookweaver.data.network.dto.ScenarioEntryDto
import com.lapcevichme.bookweaver.domain.model.BookCharacter
import com.lapcevichme.bookweaver.domain.model.BookManifest
import com.lapcevichme.bookweaver.domain.model.ChapterSummary
import com.lapcevichme.bookweaver.domain.model.DomainWordEntry
import com.lapcevichme.bookweaver.domain.model.PlaybackEntry
import com.lapcevichme.bookweaver.domain.model.ScenarioEntry
import java.util.UUID

internal fun BookManifestDto.toDomain(): BookManifest = BookManifest(
    bookName = this.bookName,
    author = this.author,
    characterVoices = this.characterVoices,
    defaultNarratorVoice = this.defaultNarratorVoice ?: "narrator_default",
    coverUrl = this.posterUrl
)

internal fun CharacterDto.toDomain(): BookCharacter = BookCharacter(
    id = try {
        UUID.fromString(this.id)
    } catch (e: Exception) {
        UUID.randomUUID()
    },
    name = this.name,
    description = this.description,
    spoilerFreeDescription = this.spoilerFreeDescription,
    aliases = this.aliases,
    chapterMentions = this.chapterMentions
)

internal fun ChapterSummaryDto.toDomain(): ChapterSummary = ChapterSummary(
    chapterId = this.chapterId,
    teaser = this.teaser,
    synopsis = this.synopsis
)

internal fun ScenarioEntryDto.toDomain(): ScenarioEntry = ScenarioEntry(
    id = try {
        UUID.fromString(this.id)
    } catch (e: Exception) {
        UUID.randomUUID()
    },
    type = this.type,
    text = this.text,
    speaker = this.speaker,
    emotion = this.emotion,
    ambient = this.ambient,
    audioFile = this.audioFile
)

internal fun DomainWordEntryDto.toDomain(): DomainWordEntry = DomainWordEntry(
    word = this.word,
    start = this.start,
    end = this.end
)

internal fun PlaybackEntryDto.toDomain(): PlaybackEntry = PlaybackEntry(
    id = this.id,
    audioFile = this.audioFile,
    text = this.text,
    startMs = this.startMs,
    endMs = this.endMs,
    words = this.words.map { it.toDomain() },
    speaker = this.speaker,
    ambient = this.ambient,
    emotion = this.emotion,
    type = this.type
)
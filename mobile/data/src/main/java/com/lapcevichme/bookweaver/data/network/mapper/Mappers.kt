package com.lapcevichme.bookweaver.data.network.mapper

import com.lapcevichme.bookweaver.data.network.dto.BookManifestDto
import com.lapcevichme.bookweaver.data.network.dto.ChapterSummaryDto
import com.lapcevichme.bookweaver.data.network.dto.CharacterDto
import com.lapcevichme.bookweaver.data.network.dto.ScenarioEntryDto
import com.lapcevichme.bookweaver.domain.model.BookCharacter
import com.lapcevichme.bookweaver.domain.model.BookManifest
import com.lapcevichme.bookweaver.domain.model.ChapterSummary
import com.lapcevichme.bookweaver.domain.model.ScenarioEntry
import java.util.UUID

// Эти функции также internal, чтобы быть доступными внутри модуля, но не снаружи.

internal fun BookManifestDto.toDomain(): BookManifest = BookManifest(
    bookName = this.bookName,
    characterVoices = this.characterVoices,
    defaultNarratorVoice = this.defaultNarratorVoice
)

internal fun CharacterDto.toDomain(): BookCharacter = BookCharacter(
    id = UUID.fromString(this.id),
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
    id = UUID.fromString(this.id),
    type = this.type,
    text = this.text,
    speaker = this.speaker,
    emotion = this.emotion,
    ambient = this.ambient,
    audioFile = this.audioFile
)

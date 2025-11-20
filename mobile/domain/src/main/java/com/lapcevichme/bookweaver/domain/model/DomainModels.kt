package com.lapcevichme.bookweaver.domain.model

import java.util.UUID

// TODO: подумать насчет nullability всех полей.

/**
 * Упрощенная модель книги для отображения в библиотеке.
 * localPath, coverPath может быть null, если книга "в облаке"
 */
data class Book(
    val id: String,
    val title: String,
    val author: String?,
    val localPath: String?,
    val coverPath: String?,
    val source: BookSource
)

/**
 * Полная, детальная модель книги со всеми связанными данными.
 * Загружается, когда пользователь открывает конкретную книгу.
 */
data class BookDetails(
    val manifest: BookManifest,
    val bookCharacters: List<BookCharacter>,
    val summaries: Map<String, ChapterSummary>,
    val chapters: List<Chapter>
)


/**
 * Метаданные всей книги. Аналог BookManifest из бэкенда.
 */
data class BookManifest(
    val bookName: String,
    val author: String?,
    val characterVoices: Map<String, String> = emptyMap(),
    val defaultNarratorVoice: String,
    val coverUrl: String? = null
)

/**
 * Модель главы книги.
 * audioDirectoryPath, scenarioPath, subtitlesPath могут быть null, если книга "в облаке"
 */
data class Chapter(
    val id: String,
    val title: String,
    val downloadState: DownloadState,
    val audioDirectoryPath: String?,
    val scenarioPath: String?,
    val subtitlesPath: String?,
    val volumeNumber: Int? = null
)


/**
 * Краткое содержание главы. Аналог ChapterSummary.
 */
data class ChapterSummary(
    val chapterId: String,
    val teaser: String,
    val synopsis: String
)

/**
 * Модель персонажа. Аналог BookCharacter из бэкенда.
 */
data class BookCharacter(
    val id: UUID,
    val name: String,
    val description: String,
    val spoilerFreeDescription: String,
    val aliases: List<String> = emptyList(),
    val chapterMentions: Map<String, String> = emptyMap()
)

/**
 * Одна запись (строка) в сценарии. Аналог ScenarioEntry.
 */
data class ScenarioEntry(
    val id: UUID,
    val type: String, // "dialogue" или "narration"
    val text: String,
    val speaker: String,
    val emotion: String?,
    val ambient: String = "none",
    val audioFile: String?
)

/**
 * Domain-модель, объединяющая всю информацию о главе.
 */
data class ChapterDetails(
    val summary: ChapterSummary?,
    val scenario: List<ScenarioEntry>,
    val originalText: String
)

/**
 * Модель, содержащая пути к медиа-файлам главы.
 * Эти пути абсолютные и готовы для использования.
 */
data class ChapterMedia(
    val subtitlesPath: String?,
    val audioDirectoryPath: String
)

/**
 * Модель, содержащая всю информацию для плеера
 */
data class PlayerChapterInfo(
    val bookTitle: String,
    val chapterTitle: String,
    val coverPath: String?,
    val media: ChapterMedia,
    val lastListenedPosition: Long
)


/**
 * Чистая доменная модель для одного слова в субтитрах.
 */
data class DomainWordEntry(
    val word: String,
    val start: Long,
    val end: Long
)

/**
 * Чистая доменная "объединенная" модель.
 * Содержит ВСЕ данные, необходимые для воспроизведения.
 */
data class PlaybackEntry(
    val id: String,
    val audioFile: String,
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val words: List<DomainWordEntry>,
    val speaker: String,
    val ambient: String,
    val emotion: String?,
    val type: String
)
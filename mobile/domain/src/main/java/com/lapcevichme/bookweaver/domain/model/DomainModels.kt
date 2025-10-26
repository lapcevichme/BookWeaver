package com.lapcevichme.bookweaver.domain.model

import java.util.UUID

// TODO: подумать насчет nullability всех полей.

/**
 * Упрощенная модель книги для отображения в библиотеке.
 *
 * @param id Уникальный идентификатор книги (например, название папки "kusuriya-no-hitorigoto-ln-novel").
 * @param title Человекочитаемое название книги.
 * @param author Автор книги.
 * @param coverPath Локальный путь к файлу обложки.
 * @param localPath Путь к корневой папке распакованной книги на устройстве.
 */
data class Book(
    val id: String,
    val title: String,
    val author: String?,
    val coverPath: String?,
    val localPath: String
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
 *
 * @param bookName Название проекта книги.
 * @param author Автор книги.
 * @param characterVoices Сопоставление ID персонажа и ID голоса.
 * @param defaultNarratorVoice Голос рассказчика по умолчанию.
 */
data class BookManifest(
    val bookName: String,
    val author: String?,
    val characterVoices: Map<String, String> = emptyMap(),
    val defaultNarratorVoice: String
)

/**
 * Модель главы книги.
 *
 * @param id Идентификатор главы (например, "vol_1_chap_1").
 * @param title Название главы (можно взять из `ChapterSummary.teaser`).
 * @param audioDirectoryPath Путь к папке с аудиофайлами главы (например, ".../vol_1_chap_1/audio").
 * @param scenarioPath Путь к файлу сценария (scenario.json).
 * @param subtitlesPath Путь к файлу субтитров (subtitles.json).
 */
data class Chapter(
    val id: String,
    val title: String,
    val audioDirectoryPath: String,
    val scenarioPath: String,
    val subtitlesPath: String?
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
    val media: ChapterMedia
)

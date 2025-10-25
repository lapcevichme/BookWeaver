package com.lapcevichme.bookweaver.features.chapterdetails

import com.lapcevichme.bookweaver.core.service.parsing.WordEntry

data class UiScenarioEntry(
    val id: String,
    val speaker: String,
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val words: List<WordEntry>
)

data class UiChapterDetails(
    val teaser: String,
    val synopsis: String,
    val scenario: List<UiScenarioEntry>,
    val originalText: String
)
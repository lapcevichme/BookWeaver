package com.lapcevichme.bookweaverdesktop.domain.model

/**
 * Перечисление типов артефактов, относящихся ко всей книге (проекту).
 */
enum class BookArtifact {
    MANIFEST,
    CHARACTER_ARCHIVE,
    SUMMARY_ARCHIVE
}

/**
 * Перечисление типов артефактов, относящихся к отдельной главе.
 */
enum class ChapterArtifact {
    SCENARIO,
    SUBTITLES
}

package com.lapcevichme.bookweaver.features.characterdetails

import com.lapcevichme.bookweaver.domain.model.BookCharacter


data class UiChapterMention(
    val chapterTitle: String,
    val summary: String
)

data class UiCharacterDetails(
    val name: String,
    val description: String,
    val spoilerFreeDescription: String,
    val aliases: List<String>,
    val chapterMentions: List<UiChapterMention>
)


fun BookCharacter.toUiCharacterDetails(): UiCharacterDetails {
    val mentions = this.chapterMentions.map { (chapterId, summary) ->
        UiChapterMention(
            chapterTitle = formatChapterIdToTitle(chapterId), // "Том X, Глава Y"
            summary = summary
        )
    }
    return UiCharacterDetails(
        name = this.name,
        description = this.description,
        spoilerFreeDescription = this.spoilerFreeDescription,
        aliases = this.aliases,
        chapterMentions = mentions
    )
}

private fun formatChapterIdToTitle(chapterId: String): String {
    return try {
        val parts = chapterId.split("_")
        "Том ${parts[1]}, Глава ${parts[3]}"
    } catch (_: Exception) {
        chapterId
    }
}

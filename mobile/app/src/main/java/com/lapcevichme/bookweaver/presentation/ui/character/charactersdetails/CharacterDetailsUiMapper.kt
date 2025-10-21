package com.lapcevichme.bookweaver.presentation.ui.character.charactersdetails

import com.lapcevichme.bookweaver.domain.model.BookCharacter

/**
 * UI-модель для одного упоминания персонажа в главе.
 */
data class UiChapterMention(
    val chapterTitle: String,
    val summary: String
)

/**
 * UI-модель для детальной информации о персонаже.
 */
data class UiCharacterDetails(
    val name: String,
    val description: String, // Полное описание со спойлерами
    val aliases: List<String>,
    val chapterMentions: List<UiChapterMention>
)

/**
 * Маппер из domain-модели Character в UI-модель UiCharacterDetails.
 */
fun BookCharacter.toUiCharacterDetails(): UiCharacterDetails {
    val mentions = this.chapterMentions.map { (chapterId, summary) ->
        UiChapterMention(
            chapterTitle = formatChapterIdToTitle(chapterId),
            summary = summary
        )
    }
    return UiCharacterDetails(
        name = this.name,
        description = this.description,
        aliases = this.aliases,
        chapterMentions = mentions
    )
}

private fun formatChapterIdToTitle(chapterId: String): String {
    return try {
        val parts = chapterId.split("_")
        "Том ${parts[1]}, Глава ${parts[3]}"
    } catch (e: Exception) {
        chapterId
    }
}

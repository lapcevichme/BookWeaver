package com.lapcevichme.bookweaver.features.characters

import com.lapcevichme.bookweaver.domain.model.BookCharacter

/**
 * UI-модель для отображения одного персонажа в списке.
 */
data class UiCharacter(
    val id: String,
    val name: String,
    val description: String
)

/**
 * Маппер из domain-модели Character в UI-модель UiCharacter.
 */
fun BookCharacter.toUiCharacter(): UiCharacter {
    return UiCharacter(
        id = this.id.toString(),
        name = this.name,
        // Используем описание без спойлеров для списка
        description = this.spoilerFreeDescription
    )
}

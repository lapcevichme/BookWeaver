package com.lapcevichme.bookweaver.features.characters

import com.lapcevichme.bookweaver.domain.model.BookCharacter


data class UiCharacter(
    val id: String,
    val name: String,
    val description: String
)


fun BookCharacter.toUiCharacter(): UiCharacter {
    return UiCharacter(
        id = this.id.toString(),
        name = this.name,
        description = this.spoilerFreeDescription
    )
}

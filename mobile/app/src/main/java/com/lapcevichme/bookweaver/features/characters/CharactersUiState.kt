package com.lapcevichme.bookweaver.features.characters

data class CharactersUiState(
    val isLoading: Boolean = true,
    val characters: List<UiCharacter> = emptyList(),
    val error: String? = null
)
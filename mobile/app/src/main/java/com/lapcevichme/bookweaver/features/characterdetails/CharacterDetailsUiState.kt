package com.lapcevichme.bookweaver.features.characterdetails

data class CharacterDetailsUiState(
    val isLoading: Boolean = true,
    val characterDetails: UiCharacterDetails? = null,
    val error: String? = null
)
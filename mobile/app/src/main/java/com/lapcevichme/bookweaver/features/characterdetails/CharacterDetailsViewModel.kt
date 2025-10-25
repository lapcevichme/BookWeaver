package com.lapcevichme.bookweaver.features.characterdetails

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.bookweaver.domain.usecase.books.GetCharacterDetailsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject


@HiltViewModel
class CharacterDetailsViewModel @Inject constructor(
    private val getCharacterDetailsUseCase: GetCharacterDetailsUseCase,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val bookId: String = checkNotNull(savedStateHandle["bookId"])
    private val characterId: String = checkNotNull(savedStateHandle["characterId"])

    private val _uiState = MutableStateFlow(CharacterDetailsUiState())
    val uiState = _uiState.asStateFlow()

    init {
        loadCharacterDetails()
    }

    private fun loadCharacterDetails() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            getCharacterDetailsUseCase(bookId, characterId)
                .onSuccess { character ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            characterDetails = character.toUiCharacterDetails()
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message)
                    }
                }
        }
    }
}

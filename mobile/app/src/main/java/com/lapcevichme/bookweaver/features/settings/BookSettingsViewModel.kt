package com.lapcevichme.bookweaver.features.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.bookweaver.domain.usecase.books.DeleteBookUseCase
import com.lapcevichme.bookweaver.domain.usecase.books.GetBookDetailsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject



@HiltViewModel
class BookSettingsViewModel @Inject constructor(
    private val deleteBookUseCase: DeleteBookUseCase,
    private val getBookDetailsUseCase: GetBookDetailsUseCase,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val bookId: String = checkNotNull(savedStateHandle["bookId"])
    private val _uiState = MutableStateFlow(BookSettingsUiState())
    val uiState = _uiState.asStateFlow()

    init {
        loadBookTitle()
    }

    fun onEvent(event: BookSettingsEvent) {
        when (event) {
            BookSettingsEvent.DeleteClicked -> _uiState.update { it.copy(showDeleteConfirmDialog = true) }
            BookSettingsEvent.DeleteCancelled -> _uiState.update { it.copy(showDeleteConfirmDialog = false) }
            BookSettingsEvent.DeleteConfirmed -> {
                _uiState.update { it.copy(showDeleteConfirmDialog = false) }
                deleteBook()
            }

            BookSettingsEvent.DeletionResultHandled -> _uiState.update { it.copy(deletionResult = null) }
        }
    }

    private fun loadBookTitle() {
        viewModelScope.launch {
            getBookDetailsUseCase(bookId).onSuccess { details ->
                _uiState.update { it.copy(bookTitle = details.manifest.bookName) }
            }
        }
    }

    private fun deleteBook() {
        viewModelScope.launch {
            val result = deleteBookUseCase(bookId)
            _uiState.update { it.copy(deletionResult = result) }
        }
    }
}

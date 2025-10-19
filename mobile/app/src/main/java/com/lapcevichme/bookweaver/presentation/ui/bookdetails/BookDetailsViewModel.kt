package com.lapcevichme.bookweaver.presentation.ui.bookdetails

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.bookweaver.domain.usecase.books.GetBookDetailsUseCase
import com.lapcevichme.bookweaver.presentation.ui.book_details.mapper.UiBookDetails
import com.lapcevichme.bookweaver.presentation.ui.book_details.mapper.toUiBookDetails
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BookDetailsUiState(
    val isLoading: Boolean = true,
    val bookId: String? = null, // <-- ДОБАВЛЕНО
    val bookDetails: UiBookDetails? = null,
    val error: String? = null
)

@HiltViewModel
class BookDetailsViewModel @Inject constructor(
    private val getBookDetailsUseCase: GetBookDetailsUseCase,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(BookDetailsUiState())
    val uiState = _uiState.asStateFlow()

    init {
        val bookId: String? = savedStateHandle.get<String>("bookId")
        _uiState.update { it.copy(bookId = bookId) } // <-- ДОБАВЛЕНО

        if (bookId != null) {
            loadBookDetails(bookId)
        } else {
            _uiState.update { it.copy(isLoading = false, error = "ID книги не найден") }
        }
    }

    private fun loadBookDetails(bookId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            getBookDetailsUseCase(bookId)
                .onSuccess { bookDetails ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            bookDetails = bookDetails.toUiBookDetails()
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message ?: "Произошла неизвестная ошибка")
                    }
                }
        }
    }
}

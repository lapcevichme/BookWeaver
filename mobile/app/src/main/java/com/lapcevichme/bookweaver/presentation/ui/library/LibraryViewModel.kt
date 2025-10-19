package com.lapcevichme.bookweaver.presentation.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.bookweaver.domain.usecase.books.GetLocalBooksUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class LibraryUiState(
    val isLoading: Boolean = true,
    val books: List<UiBook> = emptyList()
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    getLocalBooksUseCase: GetLocalBooksUseCase
) : ViewModel() {

    val uiState: StateFlow<LibraryUiState> = getLocalBooksUseCase()
        .map { domainBooks ->
            LibraryUiState(
                isLoading = false,
                books = domainBooks.map { it.toUiBook() }
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = LibraryUiState(isLoading = true)
        )

    fun onBookClicked(book: UiBook) {
        // TODO: Реализовать навигацию на экран деталей книги
        println("Clicked on book: ${book.title}")
    }
}

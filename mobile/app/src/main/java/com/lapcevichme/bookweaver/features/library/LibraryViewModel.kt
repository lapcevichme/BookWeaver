package com.lapcevichme.bookweaver.features.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.bookweaver.domain.usecase.books.GetLocalBooksUseCase
import com.lapcevichme.bookweaver.domain.usecase.books.SetActiveBookUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject


@HiltViewModel
class LibraryViewModel @Inject constructor(
    getLocalBooksUseCase: GetLocalBooksUseCase,
    private val setActiveBookUseCase: SetActiveBookUseCase
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

    private val _navigationEvent = Channel<NavigationEvent>()
    val navigationEvent = _navigationEvent.receiveAsFlow()

    fun onEvent(event: LibraryEvent) {
        when (event) {
            is LibraryEvent.BookSelected -> {
                viewModelScope.launch {
                    // Используем UseCase для сохранения ID активной книги
                    setActiveBookUseCase(event.bookId)
                    // И только после этого отправляем событие навигации
                    _navigationEvent.send(NavigationEvent.NavigateToBookHub)
                }
            }

            LibraryEvent.Refresh -> {
                // TODO: Implement refresh logic
            }
        }
    }
}


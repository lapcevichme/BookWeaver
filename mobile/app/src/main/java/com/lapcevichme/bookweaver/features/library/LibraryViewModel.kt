package com.lapcevichme.bookweaver.features.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.bookweaver.domain.usecase.books.GetLastListenedChapterIdUseCase
import com.lapcevichme.bookweaver.domain.usecase.books.GetLocalBooksUseCase
import com.lapcevichme.bookweaver.domain.usecase.books.SetActiveBookUseCase
import com.lapcevichme.bookweaver.domain.usecase.player.SetActiveChapterUseCase
import com.lapcevichme.bookweaver.domain.usecase.theme.GenerateBookThemeUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
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
    private val setActiveBookUseCase: SetActiveBookUseCase,
    private val setActiveChapterUseCase: SetActiveChapterUseCase,
    private val getLastListenedChapterIdUseCase: GetLastListenedChapterIdUseCase,
    private val generateBookThemeUseCase: GenerateBookThemeUseCase
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
                    // Асинхронно получаем последнюю главу для ВЫБРАННОЙ книги
                    val lastChapterId = getLastListenedChapterIdUseCase(event.bookId)

                    // Устанавливаем эту главу (может быть null, и это ок)
                    setActiveChapterUseCase(lastChapterId)

                    // Теперь устанавливаем активную книгу
                    setActiveBookUseCase(event.bookId)
                    _navigationEvent.send(NavigationEvent.NavigateToBookHub)

                    // "Тяжелую" генерацию темы запускаем в фоне
                    launch(Dispatchers.IO) {
                        val coverPath =
                            uiState.value.books.find { it.id == event.bookId }?.coverPath
                        generateBookThemeUseCase(event.bookId, coverPath)
                    }
                }
            }

            LibraryEvent.Refresh -> {
                // TODO: Implement refresh logic
            }
        }
    }
}


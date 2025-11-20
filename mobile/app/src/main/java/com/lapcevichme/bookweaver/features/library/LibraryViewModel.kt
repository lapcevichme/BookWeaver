package com.lapcevichme.bookweaver.features.library

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.bookweaver.domain.repository.ServerRepository
import com.lapcevichme.bookweaver.domain.usecase.books.GetBooksUseCase
import com.lapcevichme.bookweaver.domain.usecase.books.GetLastListenedChapterIdUseCase
import com.lapcevichme.bookweaver.domain.usecase.books.SetActiveBookUseCase
import com.lapcevichme.bookweaver.domain.usecase.books.SyncLibraryUseCase
import com.lapcevichme.bookweaver.domain.usecase.player.SetActiveChapterUseCase
import com.lapcevichme.bookweaver.domain.usecase.theme.GenerateBookThemeUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    getBooksUseCase: GetBooksUseCase,
    private val syncLibraryUseCase: SyncLibraryUseCase,
    private val setActiveBookUseCase: SetActiveBookUseCase,
    private val setActiveChapterUseCase: SetActiveChapterUseCase,
    private val getLastListenedChapterIdUseCase: GetLastListenedChapterIdUseCase,
    private val generateBookThemeUseCase: GenerateBookThemeUseCase,
    // 1. Инжектим репозиторий для доступа к токену
    private val serverRepository: ServerRepository
) : ViewModel() {

    private data class AsyncState(val isLoading: Boolean, val isRefreshing: Boolean)
    private val _asyncState = MutableStateFlow(AsyncState(isLoading = true, isRefreshing = false))

    private val _navigationEvent = Channel<NavigationEvent>()
    val navigationEvent = _navigationEvent.receiveAsFlow()

    val uiState: StateFlow<LibraryUiState> = combine(
        _asyncState,
        getBooksUseCase().map { domainBooks -> domainBooks.map { it.toUiBook() } },
        // 2. Добавляем поток соединения в combine
        serverRepository.getServerConnection()
    ) { asyncState, books, connection ->
        LibraryUiState(
            isLoading = asyncState.isLoading,
            isRefreshing = asyncState.isRefreshing,
            books = books,
            // 3. Передаем токен в UI State. Теперь LibraryScreen сможет добавлять заголовок.
            authToken = connection?.token
        )
    }
        .map { it.copy(isLoading = false) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = LibraryUiState(isLoading = true)
        )

    fun onEvent(event: LibraryEvent) {
        when (event) {
            is LibraryEvent.BookSelected -> {
                viewModelScope.launch {
                    val lastChapterId = getLastListenedChapterIdUseCase(event.bookId)

                    setActiveChapterUseCase(lastChapterId)
                    setActiveBookUseCase(event.bookId)
                    _navigationEvent.send(NavigationEvent.NavigateToBookHub)

                    launch(Dispatchers.IO) {
                        val coverPath = uiState.value.books.find { it.id == event.bookId }?.coverPath
                        generateBookThemeUseCase(event.bookId, coverPath)
                    }
                }
            }

            LibraryEvent.Refresh -> {
                viewModelScope.launch {
                    _asyncState.update { it.copy(isRefreshing = true) }

                    val result = syncLibraryUseCase()
                    if (result.isFailure) {
                        Log.e("LibraryVM", "Sync failed", result.exceptionOrNull())
                    }

                    _asyncState.update { it.copy(isRefreshing = false) }
                }
            }
        }
    }
}
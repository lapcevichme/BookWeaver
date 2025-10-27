package com.lapcevichme.bookweaver.features.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.bookweaver.domain.usecase.books.GetLocalBooksUseCase
import com.lapcevichme.bookweaver.domain.usecase.books.SetActiveBookUseCase
// --- 1. ЗАМЕНЯЕМ ИМПОРТ ---
// import com.lapcevichme.bookweaver.domain.usecase.theme.UpdateThemeFromCoverUseCase // <-- УДАЛЕНО
import com.lapcevichme.bookweaver.domain.usecase.theme.GenerateBookThemeUseCase // <-- ДОБАВЛЕНО
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
                    // Сначала БЫСТРЫЕ операции: установить активную книгу и перейти
                    setActiveBookUseCase(event.bookId)
                    _navigationEvent.send(NavigationEvent.NavigateToBookHub)
                    // "Тяжелую" (5 сек) генерацию запускаем в ОТДЕЛЬНОМ
                    // фоновом потоке, чтобы не блокировать навигацию
                    launch(Dispatchers.IO) {
                        // Находим путь к обложке
                        val coverPath = uiState.value.books.find { it.id == event.bookId }?.coverPath
                        // Запускаем НОВЫЙ use case
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


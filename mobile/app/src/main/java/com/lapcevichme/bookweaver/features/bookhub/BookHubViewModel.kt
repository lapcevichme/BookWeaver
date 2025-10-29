package com.lapcevichme.bookweaver.features.bookhub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.bookweaver.domain.usecase.books.GetActiveBookFlowUseCase
import com.lapcevichme.bookweaver.domain.usecase.books.GetBookDetailsUseCase
import com.lapcevichme.bookweaver.domain.usecase.player.GetActiveChapterFlowUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject


@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class BookHubViewModel @Inject constructor(
    private val getBookDetailsUseCase: GetBookDetailsUseCase,
    getActiveBookFlowUseCase: GetActiveBookFlowUseCase,
    getActiveChapterFlowUseCase: GetActiveChapterFlowUseCase
) : ViewModel() {

    // sealed interface для результата, чтобы четко отследить Загрузку/Успех/Ошибку
    private sealed interface BookDetailsResult {
        data object Loading : BookDetailsResult
        data class Success(val details: UiBookDetails?) : BookDetailsResult
        data class Error(val throwable: Throwable) : BookDetailsResult
    }

    // Flow, который реагирует ТОЛЬКО на смену activeBookId и грузит детали
    private val bookDetailsResultFlow: Flow<BookDetailsResult> = getActiveBookFlowUseCase()
        .flatMapLatest { activeBookId ->
            if (activeBookId == null) {
                // Если ID книги null, эммитим успех с null-деталями
                flowOf(BookDetailsResult.Success(null))
            } else {
                // Если ID есть, грузим детали
                flow {
                    val result = getBookDetailsUseCase(activeBookId)
                    result.fold(
                        // Успешно загрузили и сразу смаппили в UI модель
                        onSuccess = { emit(BookDetailsResult.Success(it.toUiBookDetails())) },
                        // Ошибка при загрузке
                        onFailure = { emit(BookDetailsResult.Error(it)) }
                    )
                }.onStart {
                    // Показываем индикатор загрузки В НАЧАЛЕ этого flow
                    emit(BookDetailsResult.Loading)
                }
            }
        }
        .catch { e ->
            emit(BookDetailsResult.Error(e))
        }

    // Комбинируем Flow деталей, Flow активной главы и Flow ID книги
    val uiState: StateFlow<BookDetailsUiState> = combine(
        bookDetailsResultFlow,
        getActiveChapterFlowUseCase(),
        getActiveBookFlowUseCase() // Добавляем, чтобы иметь bookId в любом состоянии
    ) { bookDetailsResult, activeChapterId, activeBookId ->

        // На основе всех данных "собираем" финальный UiState.
        when (bookDetailsResult) {
            is BookDetailsResult.Loading -> BookDetailsUiState(
                isLoading = true,
                bookId = activeBookId,
                activeChapterId = activeChapterId,
                bookDetails = null,
                error = null
            )
            is BookDetailsResult.Success -> BookDetailsUiState(
                isLoading = false,
                bookId = activeBookId,
                bookDetails = bookDetailsResult.details, // Либо null, либо {Book Details}
                activeChapterId = activeChapterId,
                error = if (activeBookId != null && bookDetailsResult.details == null) "Книга не найдена" else null
            )
            is BookDetailsResult.Error -> BookDetailsUiState(
                isLoading = false,
                bookId = activeBookId,
                bookDetails = null,
                activeChapterId = activeChapterId,
                error = bookDetailsResult.throwable.message ?: "Неизвестная ошибка"
            )
        }
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            // Начальное состояние - загрузка (пока первый combine не выполнится)
            initialValue = BookDetailsUiState(isLoading = true)
        )
}

package com.lapcevichme.bookweaver.features.bookhub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.bookweaver.domain.usecase.books.GetActiveBookFlowUseCase
import com.lapcevichme.bookweaver.domain.usecase.books.GetBookDetailsUseCase
import com.lapcevichme.bookweaver.domain.usecase.player.GetActiveChapterFlowUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject


@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class BookHubViewModel @Inject constructor(
    private val getBookDetailsUseCase: GetBookDetailsUseCase,
    private val getActiveBookFlowUseCase: GetActiveBookFlowUseCase,
    private val getActiveChapterFlowUseCase: GetActiveChapterFlowUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(BookDetailsUiState())
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // Комбинируем Flow активной книги и активной главы
            getActiveBookFlowUseCase()
                .combine(getActiveChapterFlowUseCase()) { bookId, chapterId ->
                    Pair(bookId, chapterId)
                }
                .flatMapLatest { (activeBookId, activeChapterId) ->
                    // Обновляем activeChapterId в состоянии немедленно
                    _uiState.update { it.copy(activeChapterId = activeChapterId) }

                    if (activeBookId != null) {
                        // Если активная книга есть, загружаем ее детали
                        flow {
                            // Если детали еще не загружены или ID книги изменился, грузим
                            if (_uiState.value.bookDetails == null || _uiState.value.bookId != activeBookId) {
                                emit(
                                    _uiState.value.copy(
                                        isLoading = true,
                                        bookId = activeBookId,
                                        activeChapterId = activeChapterId,
                                        error = null
                                    )
                                )
                                val result = getBookDetailsUseCase(activeBookId)
                                result.fold(
                                    onSuccess = { bookDetails ->
                                        emit(
                                            BookDetailsUiState(
                                                isLoading = false,
                                                bookId = activeBookId,
                                                bookDetails = bookDetails.toUiBookDetails(),
                                                activeChapterId = activeChapterId
                                            )
                                        )
                                    },
                                    onFailure = { error ->
                                        emit(
                                            BookDetailsUiState(
                                                isLoading = false,
                                                bookId = activeBookId,
                                                error = error.message ?: "Unknown error",
                                                activeChapterId = activeChapterId
                                            )
                                        )
                                    }
                                )
                            } else {
                                // Если детали уже есть, просто обновляем ID главы и isLoading
                                emit(
                                    _uiState.value.copy(
                                        isLoading = false,
                                        bookId = activeBookId,
                                        activeChapterId = activeChapterId
                                    )
                                )
                            }
                        }
                    } else {
                        // Если активной книги нет, показываем "пустое" состояние
                        flowOf(
                            BookDetailsUiState(
                                isLoading = false,
                                error = "Активная книга не выбрана",
                                activeChapterId = activeChapterId
                            )
                        )
                    }
                }
                .catch { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message) }
                    e.printStackTrace()
                }
                .collect { newState ->
                    _uiState.value = newState
                }
        }
    }
}


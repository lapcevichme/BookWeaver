package com.lapcevichme.bookweaver.presentation.ui.book.bookdetails

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.bookweaver.domain.usecase.books.GetActiveBookFlowUseCase
import com.lapcevichme.bookweaver.domain.usecase.books.GetBookDetailsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BookDetailsUiState(
    val isLoading: Boolean = true,
    val bookId: String? = null,
    val bookDetails: UiBookDetails? = null,
    val error: String? = null
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class BookDetailsViewModel @Inject constructor(
    private val getBookDetailsUseCase: GetBookDetailsUseCase,
    private val getActiveBookFlowUseCase: GetActiveBookFlowUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(BookDetailsUiState())
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // Теперь ViewModel слушает поток активной книги
            getActiveBookFlowUseCase()
                .flatMapLatest { activeBookId ->
                    if (activeBookId != null) {
                        // Если активная книга есть, загружаем ее детали
                        flow {
                            emit(BookDetailsUiState(isLoading = true, bookId = activeBookId))
                            val result = getBookDetailsUseCase(activeBookId)
                            result.fold(
                                onSuccess = { bookDetails ->
                                    emit(
                                        BookDetailsUiState(
                                            isLoading = false,
                                            bookId = activeBookId,
                                            bookDetails = bookDetails.toUiBookDetails()
                                        )
                                    )
                                },
                                onFailure = { error ->
                                    emit(
                                        BookDetailsUiState(
                                            isLoading = false,
                                            bookId = activeBookId,
                                            error = error.message ?: "Unknown error"
                                        )
                                    )
                                }
                            )
                        }
                    } else {
                        // Если активной книги нет, показываем "пустое" состояние
                        flowOf(
                            BookDetailsUiState(
                                isLoading = false,
                                error = "Активная книга не выбрана"
                            )
                        )
                    }
                }
                .catch { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message) }
                }
                .collect { newState ->
                    _uiState.value = newState
                }
        }
    }
}

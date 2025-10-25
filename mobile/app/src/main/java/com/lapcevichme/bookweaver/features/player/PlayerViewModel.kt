package com.lapcevichme.bookweaver.features.player

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.bookweaver.domain.usecase.books.GetActiveBookFlowUseCase
import com.lapcevichme.bookweaver.domain.usecase.player.GetActiveChapterFlowUseCase
import com.lapcevichme.bookweaver.domain.usecase.player.GetPlayerChapterInfoUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject


@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    getActiveBookFlowUseCase: GetActiveBookFlowUseCase,
    getActiveChapterFlowUseCase: GetActiveChapterFlowUseCase,
    private val getPlayerChapterInfoUseCase: GetPlayerChapterInfoUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState = _uiState.asStateFlow()

    // Отслеживаем активную книгу и главу.
    // flatMapLatest гарантирует, что мы будем загружать данные
    // только когда у нас есть и bookId, и chapterId.
    init {
        viewModelScope.launch {
            getActiveBookFlowUseCase()
                .flatMapLatest { bookId ->
                    if (bookId == null) {
                        // Если книга не выбрана, очищаем состояние
                        flowOf(PlayerUiState(error = "Книга не выбрана"))
                    } else {
                        // Если книга есть, начинаем следить за главами
                        getActiveChapterFlowUseCase().map { chapterId ->
                            Pair(bookId, chapterId)
                        }
                    }
                }
                .collectLatest { result ->
                    when (result) {
                        is PlayerUiState -> {
                            _uiState.value = result
                        }

                        is Pair<*, *> -> {
                            val bookId = result.first as? String
                            val chapterId = result.second as? String?

                            if (bookId == null) {
                                _uiState.value =
                                    PlayerUiState(error = "Внутренняя ошибка: неверный ID книги")
                            } else if (chapterId == null) {
                                _uiState.value = PlayerUiState(error = "Глава не выбрана")
                            } else {
                                loadChapterInfo(bookId, chapterId)
                            }
                        }

                        else -> {
                            Log.w("PlayerViewModel", "Неизвестный тип в потоке: $result")
                            _uiState.value = PlayerUiState(error = "Неизвестная ошибка")
                        }
                    }
                }
        }
    }

    private fun loadChapterInfo(bookId: String, chapterId: String) {
        if (_uiState.value.chapterInfo?.media?.subtitlesPath?.contains(chapterId) == true) {
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            Log.d("PlayerViewModel", "Загрузка информации для $bookId / $chapterId")
            getPlayerChapterInfoUseCase(bookId, chapterId)
                .onSuccess { info ->
                    _uiState.update {
                        it.copy(isLoading = false, chapterInfo = info)
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message)
                    }
                    error.printStackTrace()
                }
        }
    }
}

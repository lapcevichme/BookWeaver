package com.lapcevichme.bookweaver.presentation.ui.player

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.bookweaver.domain.model.PlayerChapterInfo
import com.lapcevichme.bookweaver.domain.usecase.books.GetActiveBookFlowUseCase
import com.lapcevichme.bookweaver.domain.usecase.player.GetActiveChapterFlowUseCase
import com.lapcevichme.bookweaver.domain.usecase.player.GetPlayerChapterInfoUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlayerUiState(
    val isLoading: Boolean = false,
    val chapterInfo: PlayerChapterInfo? = null,
    val error: String? = null
)

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
                    if (result is PlayerUiState) {
                        _uiState.value = result
                        return@collectLatest
                    }

                    val (bookId, chapterId) = result as Pair<String, String?>

                    if (chapterId == null) {
                        _uiState.value = PlayerUiState(error = "Глава не выбрана")
                    } else {
                        // У нас есть все, загружаем медиа
                        loadChapterInfo(bookId, chapterId)
                    }
                }
        }
    }

    private fun loadChapterInfo(bookId: String, chapterId: String) {
        // Проверяем, не загружена ли уже эта глава
        if (_uiState.value.chapterInfo?.media?.audioPath?.contains(chapterId) == true) {
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

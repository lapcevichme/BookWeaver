package com.lapcevichme.bookweaver.features.player

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.bookweaver.domain.usecase.books.GetActiveBookFlowUseCase
import com.lapcevichme.bookweaver.domain.usecase.player.GetActiveChapterFlowUseCase
import com.lapcevichme.bookweaver.domain.usecase.player.GetPlayerChapterInfoUseCase
import com.lapcevichme.bookweaver.domain.usecase.player.SetActiveChapterUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject


@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    getActiveBookFlowUseCase: GetActiveBookFlowUseCase,
    getActiveChapterFlowUseCase: GetActiveChapterFlowUseCase,
    private val getPlayerChapterInfoUseCase: GetPlayerChapterInfoUseCase,
    private val setActiveChapterUseCase: SetActiveChapterUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                getActiveBookFlowUseCase(),
                getActiveChapterFlowUseCase()
            ) { bookId, chapterId ->
                Pair(bookId, chapterId)
            }
                // Игнорируем состояние (book, null), но пропускаем (book, chapter) и (null, ...)
                .filter { (bookId, chapterId) ->
                    (bookId != null && chapterId != null) || bookId == null
                }
                .distinctUntilChanged() // Игнорируем повторные эмиссии
                .collectLatest { (bookId, chapterId) -> // Оставляем collectLatest
                    val currentState = _uiState.value

                    // Сценарий 1: Книга или глава не выбраны (null)
                    if (bookId == null || chapterId == null) {
                        // Если VM и так пуст, ничего не делаем (избегаем лишних апдейтов)
                        if (currentState.chapterInfo == null && currentState.bookId == null) {
                            return@collectLatest
                        }

                        // Иначе, очищаем состояние VM.
                        Log.d(
                            "PlayerViewModel",
                            "INIT: Active book/chapter is null. Clearing VM state."
                        )
                        _uiState.update {
                            it.copy(
                                error = if (bookId == null) "Книга не выбрана" else "Глава не выбрана",
                                chapterInfo = null,
                                isLoading = false,
                                bookId = bookId, // null
                                chapterId = chapterId // может быть null
                            )
                        }
                        return@collectLatest
                    }

                    // Сценарий 3: Пришли новые ID. Начинаем пассивную загрузку.
                    Log.d(
                        "PlayerViewModel",
                        "INIT: (Distinct) Активная пара (книга/глава) изменилась. Пассивная загрузка."
                    )

                    val isChapterInfoStale =
                        currentState.chapterInfo != null && currentState.bookId != bookId
                    val infoToUpdate = if (isChapterInfoStale) null else currentState.chapterInfo

                    _uiState.update {
                        it.copy(
                            isLoading = true,
                            error = null,
                            bookId = bookId,
                            chapterId = chapterId,
                            chapterInfo = infoToUpdate
                        )
                    }

                    getPlayerChapterInfoUseCase(bookId, chapterId)
                        .onSuccess { info ->
                            _uiState.update {
                                it.copy(
                                    isLoading = false, chapterInfo = info, bookId = bookId,
                                    chapterId = chapterId
                                )
                            }
                        }
                        .onFailure { error ->
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    error = error.message,
                                    bookId = bookId,
                                    chapterId = chapterId,
                                    chapterInfo = null
                                )
                            }
                            error.printStackTrace()
                        }
                }
        }
    }

    fun playChapter(bookId: String, chapterId: String, seekToPositionMs: Long? = null) {
        Log.d("PlayerViewModel", "PlayChapter: $bookId / $chapterId / seek: $seekToPositionMs")
        val newCommand = LoadCommand(playWhenReady = true, seekToPositionMs = seekToPositionMs)

        viewModelScope.launch {
            setActiveChapterUseCase(chapterId)
        }

        val isSameChapter =
            _uiState.value.chapterInfo?.media?.subtitlesPath?.contains(chapterId) == true

        if (isSameChapter) {
            Log.d("PlayerViewModel", "playChapter: Та же глава, просто обновляем команду")
            _uiState.update { it.copy(loadCommand = newCommand) }
        } else {
            Log.d(
                "PlayerViewModel",
                "playChapter: Другая глава. Принудительная загрузка с командой play."
            )
            loadChapterInfoForPlay(bookId, chapterId, newCommand)
        }
    }

    fun onMediaSet() {
        _uiState.update { it.copy(loadCommand = null) }
    }

    private fun loadChapterInfoForPlay(
        bookId: String, chapterId: String, loadCommand: LoadCommand
    ) {
        viewModelScope.launch {
            // Устанавливаем isLoading и loadCommand.
            _uiState.update {
                it.copy(
                    isLoading = true, error = null, loadCommand = loadCommand, bookId = bookId,
                    chapterId = chapterId
                )
            }
            Log.d(
                "PlayerViewModel",
                "loadChapterInfoForPlay: Загрузка информации для $bookId / $chapterId"
            )
            getPlayerChapterInfoUseCase(bookId, chapterId)
                .onSuccess { info ->
                    _uiState.update {
                        // Cохраняем команду И chapterInfo, когда данные успешно загружены
                        it.copy(
                            isLoading = false,
                            chapterInfo = info,
                            loadCommand = loadCommand,
                            bookId = bookId,
                            chapterId = chapterId
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        // При ошибке сбрасываем команду
                        it.copy(
                            isLoading = false,
                            error = error.message,
                            loadCommand = null,
                            bookId = bookId,
                            chapterId = chapterId
                        )
                    }
                    error.printStackTrace()
                }
        }
    }
}

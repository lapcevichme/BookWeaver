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
    private val getPlayerChapterInfoUseCase: GetPlayerChapterInfoUseCase,
    private val setActiveChapterUseCase: SetActiveChapterUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // Комбинируем потоки, чтобы знать, какая глава сейчас активна
            getActiveBookFlowUseCase()
                .flatMapLatest { bookId ->
                    if (bookId == null) {
                        flowOf(Pair<String?, String?>(null, null)) // No book
                    } else {
                        getActiveChapterFlowUseCase().map { chapterId ->
                            Pair(bookId, chapterId) // Book and chapter
                        }
                    }
                }
                .collectLatest { (bookId, chapterId) ->
                    val currentState = _uiState.value

                    if (bookId == null) {
                        _uiState.update {
                            it.copy(
                                error = "Книга не выбрана",
                                chapterInfo = null,
                                isLoading = false
                            )
                        }
                        return@collectLatest
                    }
                    if (chapterId == null) {
                        _uiState.update {
                            it.copy(
                                error = "Глава не выбрана",
                                chapterInfo = null,
                                isLoading = false
                            )
                        }
                        return@collectLatest
                    }

                    // 2. Проверяем, нужно ли ГРУЗИТЬ информацию.
                    // Мы не грузим, если:
                    // - Глава уже загружена (ID тот же)
                    // - И мы НЕ в процессе загрузки (чтобы избежать двойной загрузки)
                    val isChapterAlreadyLoaded =
                        currentState.chapterInfo?.media?.subtitlesPath?.contains(chapterId) == true
                    if (isChapterAlreadyLoaded || (currentState.isLoading && currentState.loadCommand == null)) {
                        return@collectLatest
                    }

                    Log.d(
                        "PlayerViewModel",
                        "INIT: Активная глава изменилась на $chapterId. Пассивная загрузка информации."
                    )
                    _uiState.update { it.copy(isLoading = true, error = null) }
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

    fun playChapter(bookId: String, chapterId: String, seekToPositionMs: Long? = null) {
        Log.d("PlayerViewModel", "PlayChapter: $bookId / $chapterId / seek: $seekToPositionMs")
        val newCommand = LoadCommand(playWhenReady = true, seekToPositionMs = seekToPositionMs)

        // Немедленно устанавливаем эту главу как активную.
        viewModelScope.launch {
            setActiveChapterUseCase(chapterId)
        }

        // Проверяем, та же ли это глава
        val isSameChapter =
            _uiState.value.chapterInfo?.media?.subtitlesPath?.contains(chapterId) == true

        if (isSameChapter) {
            // Та же глава: информация уже есть. Просто обновляем команду.
            Log.d("PlayerViewModel", "playChapter: Та же глава, просто обновляем команду")
            _uiState.update { it.copy(loadCommand = newCommand) }
        } else {
            // Другая глава:
            // `setActiveChapterUseCase` уже вызван. `init` блок начнет пассивную загрузку info.
            // Нам нужно принудительно запустить активную загрузку (с командой),
            // которая также установит `chapterInfo`.
            Log.d(
                "PlayerViewModel",
                "playChapter: Другая глава. Принудительная загрузка с командой play."
            )
            loadChapterInfoForPlay(bookId, chapterId, newCommand)
        }
    }

    // Эта функция сбрасывает флаг после того, как Service получил команду
    fun onMediaSet() {
        _uiState.update { it.copy(loadCommand = null) }
    }

    private fun loadChapterInfoForPlay(
        bookId: String, chapterId: String, loadCommand: LoadCommand
    ) {
        viewModelScope.launch {
            // Устанавливаем isLoading и loadCommand.
            _uiState.update { it.copy(isLoading = true, error = null, loadCommand = loadCommand) }
            Log.d(
                "PlayerViewModel",
                "loadChapterInfoForPlay: Загрузка информации для $bookId / $chapterId"
            )
            getPlayerChapterInfoUseCase(bookId, chapterId)
                .onSuccess { info ->
                    _uiState.update {
                        // Cохраняем команду И chapterInfo, когда данные успешно загружены
                        it.copy(isLoading = false, chapterInfo = info, loadCommand = loadCommand)
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        // При ошибке сбрасываем команду
                        it.copy(isLoading = false, error = error.message, loadCommand = null)
                    }
                    error.printStackTrace()
                }
        }
    }
}

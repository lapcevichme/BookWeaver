package com.lapcevichme.bookweaver.features.player

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.bookweaver.domain.usecase.books.GetActiveBookFlowUseCase
import com.lapcevichme.bookweaver.domain.usecase.player.GetActiveChapterFlowUseCase
import com.lapcevichme.bookweaver.domain.usecase.player.GetAmbientVolumeUseCase
import com.lapcevichme.bookweaver.domain.usecase.player.GetPlaybackSpeedUseCase
import com.lapcevichme.bookweaver.domain.usecase.player.GetPlayerChapterInfoUseCase
import com.lapcevichme.bookweaver.domain.usecase.player.SaveAmbientVolumeUseCase
import com.lapcevichme.bookweaver.domain.usecase.player.SavePlaybackSpeedUseCase
import com.lapcevichme.bookweaver.domain.usecase.player.SetActiveChapterUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject


@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    getActiveBookFlowUseCase: GetActiveBookFlowUseCase,
    getActiveChapterFlowUseCase: GetActiveChapterFlowUseCase,
    private val getPlayerChapterInfoUseCase: GetPlayerChapterInfoUseCase,
    private val setActiveChapterUseCase: SetActiveChapterUseCase,
    private val getPlaybackSpeedUseCase: GetPlaybackSpeedUseCase,
    private val savePlaybackSpeedUseCase: SavePlaybackSpeedUseCase,
    private val getAmbientVolumeUseCase: GetAmbientVolumeUseCase,
    private val saveAmbientVolumeUseCase: SaveAmbientVolumeUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                getActiveBookFlowUseCase(),
                getActiveChapterFlowUseCase(),
                getPlaybackSpeedUseCase(),
                getAmbientVolumeUseCase()
            ) { bookId, chapterId, speed, volume ->
                Triple(Pair(bookId, chapterId), speed, volume)
            }
                .distinctUntilChanged()
                .collectLatest { (bookAndChapter, speed, volume) ->
                    val (bookId, chapterId) = bookAndChapter

                    val currentState = _uiState.value

                    // Сценарий 1: Книга не выбрана (bookId is null)
                    if (bookId == null) {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = null,
                                chapterInfo = null,
                                bookId = null,
                                chapterId = null,
                                loadCommand = null,
                                clearService = true,
                                playbackSpeed = speed,
                                ambientVolume = volume
                            )
                        }
                        return@collectLatest
                    }

                    // --- С этого момента у нас есть bookId: String ---

                    // Сценарий 2: Книга выбрана, глава - нет (chapterId is null)
                    if (chapterId == null) {
                        Log.d("PlayerViewModel", "INIT: Book selected, but no chapter.")
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = "Глава не выбрана",
                                chapterInfo = null,
                                bookId = bookId,
                                chapterId = null,
                                loadCommand = null,
                                clearService = true,
                                playbackSpeed = speed,
                                ambientVolume = volume
                            )
                        }
                        return@collectLatest
                    }

                    // --- С этого момента у нас есть и bookId: String, и chapterId: String ---

                    val isBookChanged = currentState.bookId != bookId
                    val isHotRestart = currentState.bookId == null
                    val isChapterChanged = currentState.chapterId != chapterId

                    // Сценарий 3: РЕАЛЬНАЯ смена книги (не "горячий" рестарт)
                    if (isBookChanged && !isHotRestart) {
                        Log.d(
                            "PlayerViewModel",
                            "INIT: Book changed (A->B). Issuing ClearService."
                        )
                        _uiState.update {
                            it.copy(
                                isLoading = true,
                                error = null,
                                bookId = bookId,
                                chapterId = chapterId,
                                chapterInfo = null,
                                loadCommand = null,
                                clearService = true,
                                playbackSpeed = speed,
                                ambientVolume = volume
                            )
                        }
                        loadChapterInfo(bookId, chapterId)
                        return@collectLatest
                    }

                    // --- Сценарии 4 (Рестарт) и 5 (Смена главы) ---

                    val isSameTarget = !isBookChanged && !isChapterChanged
                    val isAlreadyLoaded = currentState.chapterInfo != null && isSameTarget
                    val isPassiveLoading =
                        currentState.isLoading && currentState.loadCommand == null && isSameTarget

                    // Сценарий 4.1: "Горячий" перезапуск или избыточная эмиссия
                    if (isAlreadyLoaded || isPassiveLoading) {
                        Log.d("PlayerViewModel", "INIT: Skip. Already loaded/loading.")
                        // Все равно обновим настройки, если они изменились
                        if (currentState.playbackSpeed != speed || currentState.ambientVolume != volume) {
                            _uiState.update {
                                it.copy(playbackSpeed = speed, ambientVolume = volume)
                            }
                        }
                        return@collectLatest
                    }

                    // Сценарий 4.2: Сюда попадаем при:
                    //   a) "Горячем" рестарте (isHotRestart = true)
                    //   b) Смене главы (isChapterChanged = true)
                    //   c) Пассивной загрузке (напр. после сброса)

                    Log.d(
                        "PlayerViewModel",
                        "INIT: Passive loading $bookId / $chapterId. (BookChanged: $isBookChanged, ChapterChanged: $isChapterChanged)"
                    )

                    val infoToKeep =
                        if (isBookChanged || isChapterChanged) null else currentState.chapterInfo

                    _uiState.update {
                        it.copy(
                            isLoading = true,
                            error = null,
                            bookId = bookId,
                            chapterId = chapterId,
                            chapterInfo = infoToKeep,
                            loadCommand = null,
                            clearService = false,
                            playbackSpeed = speed,
                            ambientVolume = volume
                        )
                    }
                    loadChapterInfo(bookId, chapterId)
                }
        }
    }

    private fun loadChapterInfo(bookId: String, chapterId: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    bookId = bookId,
                    chapterId = chapterId
                )
            }
            getPlayerChapterInfoUseCase(bookId, chapterId)
                .onSuccess { info ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            chapterInfo = info,
                            bookId = bookId,
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
                            chapterId = chapterId
                        )
                    }
                    error.printStackTrace()
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

    /**
     * Вызывается из MainScaffold, когда команда `clearService` была выполнена.
     */
    fun onServiceCleared() {
        _uiState.update { it.copy(clearService = false) }
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

    fun onPlaybackSpeedChanged(newSpeed: Float) {
        viewModelScope.launch {
            savePlaybackSpeedUseCase(newSpeed)
            // UI обновится автоматически, т.к. init() слушает
            // getPlaybackSpeedUseCase()
        }
    }

    fun onAmbientVolumeChanged(newVolume: Float) {
        viewModelScope.launch {
            saveAmbientVolumeUseCase(newVolume)
            // UI обновится автоматически
        }
    }
}


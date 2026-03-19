package com.lapcevichme.bookweaver.features.player

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.bookweaver.core.PlayerState
import com.lapcevichme.bookweaver.domain.usecase.books.GetActiveBookFlowUseCase
import com.lapcevichme.bookweaver.domain.usecase.player.GetActiveChapterFlowUseCase
import com.lapcevichme.bookweaver.domain.usecase.player.GetAmbientVolumeUseCase
import com.lapcevichme.bookweaver.domain.usecase.player.GetIllustrationsEnabledUseCase
import com.lapcevichme.bookweaver.domain.usecase.player.GetPlaybackSpeedUseCase
import com.lapcevichme.bookweaver.domain.usecase.player.GetPlayerChapterInfoUseCase
import com.lapcevichme.bookweaver.domain.usecase.player.SaveAmbientVolumeUseCase
import com.lapcevichme.bookweaver.domain.usecase.player.SaveIllustrationsEnabledUseCase
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
    private val saveAmbientVolumeUseCase: SaveAmbientVolumeUseCase,
    private val getIllustrationsEnabledUseCase: GetIllustrationsEnabledUseCase,
    private val saveIllustrationsEnabledUseCase: SaveIllustrationsEnabledUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState = _uiState.asStateFlow()

    private data class PlayerInitParams(
        val bookId: String?,
        val chapterId: String?,
        val playbackSpeed: Float,
        val ambientVolume: Float,
        val illustrationsEnabled: Boolean
    )

    init {
        viewModelScope.launch {
            combine(
                getActiveBookFlowUseCase(),
                getActiveChapterFlowUseCase(),
                getPlaybackSpeedUseCase(),
                getAmbientVolumeUseCase(),
                getIllustrationsEnabledUseCase()
            ) { bookId, chapterId, speed, volume, illustrations ->
                PlayerInitParams(bookId, chapterId, speed, volume, illustrations)
            }
                .distinctUntilChanged()
                .collectLatest { params ->
                    val bookId = params.bookId
                    val chapterId = params.chapterId
                    val speed = params.playbackSpeed
                    val volume = params.ambientVolume
                    val illustrations = params.illustrationsEnabled
                    val currentState = _uiState.value

                    // Сценарий 1: Книга не выбрана
                    if (bookId == null) {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = "Книга не выбрана",
                                chapterInfo = null,
                                bookId = null,
                                chapterId = null,
                                loadCommand = null,
                                clearService = true,
                                playbackSpeed = speed,
                                ambientVolume = volume,
                                illustrationsEnabled = illustrations
                            )
                        }
                        return@collectLatest
                    }


                    // Сценарий 2: Книга выбрана, глава - нет
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
                                ambientVolume = volume,
                                illustrationsEnabled = illustrations
                            )
                        }
                        return@collectLatest
                    }

                    val isBookChanged = currentState.bookId != bookId
                    val isHotRestart = currentState.bookId == null
                    val isChapterChanged = currentState.chapterId != chapterId

                    // Сценарий 3: РЕАЛЬНАЯ смена книги
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
                                ambientVolume = volume,
                                illustrationsEnabled = illustrations
                            )
                        }
                        loadChapterInfo(bookId, chapterId)
                        return@collectLatest
                    }

                    // Сценарии 4 (Рестарт) и 5 (Смена главы)

                    val isSameTarget = !isBookChanged && !isChapterChanged
                    val isAlreadyLoaded = currentState.chapterInfo != null && isSameTarget
                    val isPassiveLoading =
                        currentState.isLoading && currentState.loadCommand == null && isSameTarget

                    // Сценарий 4.1: "Горячий" перезапуск или избыточная эмиссия
                    if (isAlreadyLoaded || isPassiveLoading) {
                        Log.d("PlayerViewModel", "INIT: Skip. Already loaded/loading.")
                        if (currentState.playbackSpeed != speed || currentState.ambientVolume != volume || currentState.illustrationsEnabled != illustrations) {
                            _uiState.update {
                                it.copy(playbackSpeed = speed, ambientVolume = volume, illustrationsEnabled = illustrations)
                            }
                        }
                        return@collectLatest
                    }

                    // Сценарий 4.2
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
                            ambientVolume = volume,
                            illustrationsEnabled = illustrations
                        )
                    }
                    loadChapterInfo(bookId, chapterId)
                }
        }
    }

    fun retry() {
        val currentState = _uiState.value
        if (currentState.bookId != null && currentState.chapterId != null) {
            _uiState.update { it.copy(clearService = true) }
            loadChapterInfo(currentState.bookId, currentState.chapterId)
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
                    if (info.media.subtitlesPath == null) {
                        Log.w("PlayerViewModel", "Глава без аудио (subtitlesPath is null).")
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = "У этой главы нет аудио.",
                                loadCommand = null,
                                bookId = bookId,
                                chapterId = chapterId,
                                chapterInfo = info,
                                clearService = true
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                chapterInfo = info,
                                bookId = bookId,
                                chapterId = chapterId
                            )
                        }
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

        val isSameChapter = _uiState.value.chapterId == chapterId

        if (isSameChapter && _uiState.value.chapterInfo != null) {
            if (_uiState.value.chapterInfo?.media?.subtitlesPath != null) {
                Log.d("PlayerViewModel", "playChapter: Та же глава, просто обновляем команду")
                _uiState.update { it.copy(loadCommand = newCommand) }
            } else {
                _uiState.update { it.copy(error = "У этой главы нет аудио.", clearService = true) }
            }
        } else {
            Log.d(
                "PlayerViewModel",
                "playChapter: Другая глава. Принудительная загрузка с командой play."
            )
            loadChapterInfoForPlay(bookId, chapterId, newCommand)
        }
    }

    fun onMediaSet() {
        Log.d("PlayerViewModel", "onMediaSet: Clearing LoadCommand (SYNC)")
        _uiState.update { it.copy(loadCommand = null) }
    }

    fun onPlayerStateChanged(playerState: PlayerState) {
        val currentState = _uiState.value
        val command = currentState.loadCommand

        if (command == null) {
            if (playerState.error != null && currentState.error == null && !currentState.clearService) {
                Log.w("PlayerViewModel", "onPlayerStateChanged: Service reported an error. (No command, just reporting)")
                _uiState.update { it.copy(error = playerState.error) }
            }
            return
        }

        val targetChapterId = currentState.chapterId
        val actualChapterId = playerState.loadedChapterId

        if (playerState.error != null) {
            Log.w("PlayerViewModel", "onPlayerStateChanged: Service reported an error during load command. Clearing command.")
            _uiState.update { it.copy(loadCommand = null, error = playerState.error) }
            return
        }

        if (targetChapterId != actualChapterId) {
            return
        }

        Log.d("PlayerViewModel", "onPlayerStateChanged: Command processed for $targetChapterId. Clearing command.")
        _uiState.update { it.copy(loadCommand = null) }
    }


    fun onServiceCleared() {
        _uiState.update { it.copy(clearService = false) }
    }

    private fun loadChapterInfoForPlay(
        bookId: String,
        chapterId: String,
        loadCommand: LoadCommand
    ) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    loadCommand = null,
                    bookId = bookId,
                    chapterId = chapterId
                )
            }
            Log.d(
                "PlayerViewModel",
                "loadChapterInfoForPlay: Загрузка информации для $bookId / $chapterId"
            )
            getPlayerChapterInfoUseCase(bookId, chapterId)
                .onSuccess { info ->
                    if (info.media.subtitlesPath == null) {
                        Log.w("PlayerViewModel", "loadChapterInfoForPlay: Success, but no subtitlesPath. Chapter has no audio.")
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = "У этой главы нет аудио.",
                                loadCommand = null,
                                bookId = bookId,
                                chapterId = chapterId,
                                chapterInfo = info,
                                clearService = true
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                chapterInfo = info,
                                loadCommand = loadCommand,
                                bookId = bookId,
                                chapterId = chapterId
                            )
                        }
                    }
                }
                .onFailure { error ->
                    _uiState.update {
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
        }
    }

    fun onAmbientVolumeChanged(newVolume: Float) {
        viewModelScope.launch {
            saveAmbientVolumeUseCase(newVolume)
        }
    }

    fun onIllustrationsEnabledChanged(enabled: Boolean) {
        viewModelScope.launch {
            saveIllustrationsEnabledUseCase(enabled)
        }
    }

    fun seekTo(positionMs: Long) {
        _uiState.update { currentState ->
            val baseCommand = currentState.loadCommand ?: LoadCommand(
                playWhenReady = false,
                seekToPositionMs = null
            )
            currentState.copy(
                loadCommand = baseCommand.copy(seekToPositionMs = positionMs)
            )
        }
    }

    fun play() {
        _uiState.update { currentState ->
            val baseCommand = currentState.loadCommand ?: LoadCommand(
                playWhenReady = false,
                seekToPositionMs = null
            )
            currentState.copy(
                loadCommand = baseCommand.copy(playWhenReady = true)
            )
        }
    }

    // Костыль для race condition
    fun seekToAndPlay(positionMs: Long) {
        Log.d("PlayerViewModel", "seekToAndPlay: Установка LoadCommand (play=true, seek=$positionMs)")
        _uiState.update { currentState ->
            val baseCommand = currentState.loadCommand ?: LoadCommand(
                playWhenReady = false,
                seekToPositionMs = null
            )
            currentState.copy(
                loadCommand = baseCommand.copy(playWhenReady = true, seekToPositionMs = positionMs)
            )
        }
    }
}
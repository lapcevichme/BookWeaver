package com.lapcevichme.bookweaver.features.player

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.bookweaver.domain.usecase.books.GetActiveBookFlowUseCase
import com.lapcevichme.bookweaver.domain.usecase.player.GetActiveChapterFlowUseCase
import com.lapcevichme.bookweaver.domain.usecase.player.GetPlayerChapterInfoUseCase
import com.lapcevichme.bookweaver.domain.usecase.player.SaveListenProgressUseCase
import com.lapcevichme.bookweaver.domain.usecase.player.SetActiveChapterUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private val setActiveChapterUseCase: SetActiveChapterUseCase,
    private val saveListenProgressUseCase: SaveListenProgressUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState = _uiState.asStateFlow()

    private var saveProgressJob: Job? = null // Для дебаунса
    private var lastSaveTimeMs: Long = 0 // Время последнего *реального* сохранения
    private val SAVE_THROTTLE_MS = 10_000L // 10 секунд (по желанию пользователя)
    private val SAVE_DEBOUNCE_MS = 1_000L // 1 секунда (для сохранения на паузе)

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

                    val isChapterInfoStale =
                        currentState.chapterInfo != null && currentState.bookId != bookId


                    if (bookId == null) {
                        _uiState.update {
                            it.copy(
                                error = "Книга не выбрана",
                                chapterInfo = null,
                                isLoading = false,
                                bookId = null,
                                chapterId = null
                            )
                        }
                        return@collectLatest
                    }
                    if (chapterId == null) {
                        _uiState.update {
                            it.copy(
                                error = "Глава не выбрана",
                                chapterInfo = null,
                                isLoading = false,
                                bookId = bookId,
                                chapterId = null

                            )
                        }
                        return@collectLatest
                    }

                    val isChapterAlreadyLoaded =
                        !isChapterInfoStale &&
                                currentState.chapterInfo?.media?.subtitlesPath?.contains(chapterId) == true

                    if (isChapterAlreadyLoaded || (currentState.isLoading && currentState.loadCommand == null)) {
                        _uiState.update { it.copy(bookId = bookId, chapterId = chapterId) }
                        return@collectLatest
                    }

                    Log.d(
                        "PlayerViewModel",
                        "INIT: Активная пара (книга/глава) изменилась. Пассивная загрузка. Stale: $isChapterInfoStale"
                    )

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

    /**
     * Сохраняет прогресс прослушивания.
     * Использует гибридную логику:
     * 1. Throttle: Сохраняет не чаще, чем раз в SAVE_THROTTLE_MS.
     * 2. Debounce: Сохраняет, если вызовы прекратились на SAVE_DEBOUNCE_MS (т.е. на паузе).
     */
    fun saveProgress(position: Long) {
        // Получаем ID
        val currentBookId = _uiState.value.bookId
        val currentChapterId = _uiState.value.chapterId
        if (currentBookId == null || currentChapterId == null || position == 0L) {
            return
        }

        // Отменяем предыдущую *запланированную* (debounce) задачу
        saveProgressJob?.cancel()

        val currentTimeMs = System.currentTimeMillis()

        // --- Логика Throttling ---
        if (currentTimeMs - lastSaveTimeMs > SAVE_THROTTLE_MS) {
            // Прошло 10 секунд, можно сохранять немедленно
            Log.d("PlayerViewModel", "SaveProgress (Throttled): $position")
            lastSaveTimeMs = currentTimeMs // Обновляем время
            // Запускаем реальное сохранение
            saveProgressJob = viewModelScope.launch {
                try {
                    saveListenProgressUseCase(currentBookId, currentChapterId, position)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } else {
            // --- Логика Debounce ---
            // 10 секунд не прошло. Просто планируем сохранение
            // (оно выполнится, если 1 сек не будет новых вызовов, т.е. на паузе)
            saveProgressJob = viewModelScope.launch {
                delay(SAVE_DEBOUNCE_MS)
                Log.d("PlayerViewModel", "SaveProgress (Debounced): $position")
                // Проверяем, что ID не изменились, пока мы ждали
                if (_uiState.value.bookId == currentBookId && _uiState.value.chapterId == currentChapterId) {
                    saveListenProgressUseCase(currentBookId, currentChapterId, position)
                    // Также обновляем время, т.к. debounce-сохранение тоже считается
                    lastSaveTimeMs = System.currentTimeMillis()
                }
            }
        }
    }


    override fun onCleared() {
        Log.d("PlayerViewModel", "onCleared")
        // Отменяем *запланированное* сохранение, если ViewModel уничтожается
        saveProgressJob?.cancel()
        super.onCleared()
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


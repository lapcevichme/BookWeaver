package com.lapcevichme.bookweaver.features.chapterdetails

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.bookweaver.domain.model.ChapterDetails
import com.lapcevichme.bookweaver.domain.model.ScenarioEntry
import com.lapcevichme.bookweaver.domain.usecase.books.GetChapterDataPathUseCase
import com.lapcevichme.bookweaver.domain.usecase.books.GetChapterOriginalTextUseCase
import com.lapcevichme.bookweaver.domain.usecase.books.GetChapterScenarioUseCase
import com.lapcevichme.bookweaver.domain.usecase.books.GetChapterSummaryUseCase
import com.lapcevichme.bookweaver.domain.usecase.player.GetChapterPlaybackDataUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChapterDetailsViewModel @Inject constructor(
    private val getPlaybackDataUseCase: GetChapterPlaybackDataUseCase,
    private val getChapterSummaryUseCase: GetChapterSummaryUseCase,
    private val getOriginalTextUseCase: GetChapterOriginalTextUseCase,
    private val getChapterScenarioUseCase: GetChapterScenarioUseCase,
    private val getChapterDataPathUseCase: GetChapterDataPathUseCase,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        private const val TAG = "ChapterDetailsViewModel"
    }

    private val bookId: String = checkNotNull(savedStateHandle["bookId"])
    private val chapterId: String = checkNotNull(savedStateHandle["chapterId"])

    private val _uiState = MutableStateFlow(ChapterDetailsUiState())
    val uiState = _uiState.asStateFlow()

    init {
        _uiState.update {
            it.copy(
                chapterTitle = formatChapterIdToTitle(chapterId),
                bookId = this.bookId,
                chapterId = this.chapterId
            )
        }
        loadChapterContent()
    }

    private fun loadChapterContent() {
        Log.d(TAG, "Start loading content for $chapterId")
        _uiState.update { it.copy(isLoading = true) }

        viewModelScope.launch {
            val playbackDeferred = async { getPlaybackDataUseCase(bookId, chapterId) }
            val scenarioDeferred = async { getChapterScenarioUseCase(bookId, chapterId) }
            val summaryDeferred = async { getChapterSummaryUseCase(bookId, chapterId) }
            val textDeferred = async { getOriginalTextUseCase(bookId, chapterId) }
            val dataPathDeferred = async { getChapterDataPathUseCase(bookId, chapterId) }

            val playbackResult = playbackDeferred.await()
            val scenarioResult = scenarioDeferred.await()
            val summaryResult = summaryDeferred.await()
            val textResult = textDeferred.await()
            val dataPath = dataPathDeferred.await()

            val summary = summaryResult.getOrNull()
            val originalText = textResult.getOrDefault("")
            val baseScenario = scenarioResult.getOrDefault(emptyList())

            val playbackDataPair = playbackResult.getOrNull()

            if (playbackResult.isFailure) {
                Log.w(TAG, "Playback data failed (possibly 404), loading in text-only mode: ${playbackResult.exceptionOrNull()?.message}")
            }

            val details = ChapterDetails(
                scenario = baseScenario,
                summary = summary,
                originalText = originalText,
                dataPath = dataPath
            )

            val audioPath = playbackDataPair?.second
            val playbackEntries = playbackDataPair?.first ?: emptyList()

            val isPathValid = !audioPath.isNullOrBlank() && !audioPath.equals("none", ignoreCase = true)

            val hasAudio = if (isPathValid) {
                val hasFileExtension = audioPath.endsWith(".mp3", true) ||
                        audioPath.endsWith(".wav", true) ||
                        audioPath.endsWith(".m4a", true) ||
                        audioPath.endsWith(".ogg", true)

                val hasInnerAudioFiles = playbackEntries.any { it.audioFile.isNotBlank() }

                // Аудио есть, если это Single File (расширение) ИЛИ Playlist (есть внутренние файлы)
                hasFileExtension || hasInnerAudioFiles
            } else {
                false
            }

            val uiDetails = if (playbackDataPair != null && hasAudio) {
                details.toUiModelWithAudio(playbackDataPair.first, audioPath)
            } else {
                // Принудительно отключаем кликабельность (isPlayable = false)
                details.toUiModelTextOnly()
            }

            if (originalText.isEmpty() && summary == null && baseScenario.isEmpty()) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "Не удалось загрузить данные главы (ни текста, ни сценария)"
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        details = uiDetails,
                        error = null
                    )
                }
            }
        }
    }

    private fun formatChapterIdToTitle(chapterId: String): String {
        return try {
            val parts = chapterId.split("_")
            "Том ${parts[1]}, Глава ${parts[3]}"
        } catch (_: Exception) {
            chapterId
        }
    }
}
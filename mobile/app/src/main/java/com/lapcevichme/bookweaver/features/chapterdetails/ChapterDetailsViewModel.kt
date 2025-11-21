package com.lapcevichme.bookweaver.features.chapterdetails

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.bookweaver.domain.model.ChapterDetails
import com.lapcevichme.bookweaver.domain.model.ScenarioEntry
import com.lapcevichme.bookweaver.domain.usecase.books.GetChapterOriginalTextUseCase
import com.lapcevichme.bookweaver.domain.usecase.books.GetChapterSummaryUseCase
import com.lapcevichme.bookweaver.domain.usecase.player.GetChapterPlaybackDataUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ChapterDetailsViewModel @Inject constructor(
    private val getPlaybackDataUseCase: GetChapterPlaybackDataUseCase,
    private val getChapterSummaryUseCase: GetChapterSummaryUseCase,
    private val getOriginalTextUseCase: GetChapterOriginalTextUseCase,
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

    /**
     * Загружает контент главы (текст/аудио), оригинал и мета-информацию параллельно.
     */
    private fun loadChapterContent() {
        Log.d(TAG, "Start loading content for $chapterId")
        _uiState.update { it.copy(isLoading = true) }

        viewModelScope.launch {
            val playbackDeferred = async { getPlaybackDataUseCase(bookId, chapterId) }
            val summaryDeferred = async { getChapterSummaryUseCase(bookId, chapterId) }
            val textDeferred = async { getOriginalTextUseCase(bookId, chapterId) }

            val playbackResult = playbackDeferred.await()
            val summaryResult = summaryDeferred.await()
            val textResult = textDeferred.await()

            // Обрабатываем основной контент (Scenario/Audio)
            // Логика: если нет playbackData (сценария), то глава считается не загруженной/сломанной.
            // Если нет оригинала или summary — это не критично, показываем что есть.
            playbackResult.fold(
                onSuccess = { (playbackData, _) ->
                    Log.d(TAG, "PlaybackData loaded successfully.")

                    val scenario = playbackData.map { entry ->
                        ScenarioEntry(
                            id = UUID.randomUUID(),
                            text = entry.text,
                            speaker = entry.speaker,
                            emotion = entry.emotion,
                            type = entry.type,
                            audioFile = entry.audioFile,
                            ambient = entry.ambient
                        )
                    }

                    val summary = summaryResult.getOrNull()
                    val originalText = textResult.getOrDefault("")

                    val details = ChapterDetails(
                        scenario = scenario,
                        summary = summary,
                        originalText = originalText
                    )

                    val uiDetails = details.toUiModelWithAudio(playbackData)

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            details = uiDetails,
                            error = null
                        )
                    }
                },
                onFailure = { e ->
                    Log.e(TAG, "Error loading playback data", e)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Ошибка загрузки данных главы: ${e.message}"
                        )
                    }
                }
            )
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
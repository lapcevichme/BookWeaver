package com.lapcevichme.bookweaver.features.chapterdetails

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.bookweaver.domain.model.ChapterDetails
import com.lapcevichme.bookweaver.domain.usecase.books.GetChapterDetailsUseCase
import com.lapcevichme.bookweaver.domain.usecase.player.GetChapterPlaybackDataUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChapterDetailsViewModel @Inject constructor(
    private val getChapterDetailsUseCase: GetChapterDetailsUseCase,
    private val getPlaybackDataUseCase: GetChapterPlaybackDataUseCase,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        private const val TAG = "ChapterDetailsViewModel"
    }

    private val bookId: String = checkNotNull(savedStateHandle["bookId"])
    private val chapterId: String = checkNotNull(savedStateHandle["chapterId"])

    private val _uiState = MutableStateFlow(ChapterDetailsUiState())
    val uiState = _uiState.asStateFlow()

    private var textOnlyDetails: ChapterDetails? = null

    init {
        _uiState.update {
            it.copy(
                chapterTitle = formatChapterIdToTitle(chapterId),
                bookId = this.bookId,
                chapterId = this.chapterId
            )
        }
        loadChapterTextContent()
    }

    /**
     * Этап 1: Загрузка только текстового контента (scenario.json).
     * Это позволяет показать экран, даже если аудио (subtitles.json) нет.
     */
    private fun loadChapterTextContent() {
        Log.d(TAG, "Этап 1: Загрузка текста (scenario.json)...")
        _uiState.update { it.copy(isLoading = true) }

        viewModelScope.launch {
            getChapterDetailsUseCase(bookId, chapterId).fold(
                onSuccess = { details ->
                    Log.d(TAG, "Этап 1: Текст успешно загружен. ${details.scenario.size} реплик.")
                    textOnlyDetails = details
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            details = details.toUiModelTextOnly(),
                            error = null
                        )
                    }
                    loadChapterAudioContent()
                },
                onFailure = { e ->
                    Log.e(TAG, "Этап 1: Ошибка загрузки текста", e)
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

    /**
     * Этап 2: Попытка загрузить и "применить" аудио-данные (subtitles.json).
     * Вызывается ПОСЛЕ Этапа 1.
     */
    private fun loadChapterAudioContent() {
        Log.d(TAG, "Этап 2: Попытка загрузки аудио (subtitles.json)...")
        val baseDetails = textOnlyDetails ?: run {
            Log.e(TAG, "Этап 2: Ошибка, textOnlyDetails is null. Загрузка аудио невозможна.")
            return
        }

        viewModelScope.launch {
            getPlaybackDataUseCase(bookId, chapterId).fold(
                onSuccess = { (playbackData, _) ->
                    Log.d(TAG, "Этап 2: Аудио-данные успешно загружены и применены.")
                    _uiState.update {
                        it.copy(
                            details = baseDetails.toUiModelWithAudio(playbackData)
                        )
                    }
                },
                onFailure = { e ->
                    Log.w(TAG, "Этап 2: Аудио-данные не найдены (subtitles.json). Глава в режиме 'только чтение'.")
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


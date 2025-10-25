package com.lapcevichme.bookweaver.features.chapterdetails

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.bookweaver.core.service.parsing.SubtitleEntry
import com.lapcevichme.bookweaver.domain.model.ChapterDetails
import com.lapcevichme.bookweaver.domain.model.PlayerChapterInfo
import com.lapcevichme.bookweaver.domain.model.ScenarioEntry
import com.lapcevichme.bookweaver.domain.usecase.books.GetChapterDetailsUseCase
import com.lapcevichme.bookweaver.domain.usecase.player.GetPlayerChapterInfoUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject


@HiltViewModel
class ChapterDetailsViewModel @Inject constructor(
    private val getChapterDetailsUseCase: GetChapterDetailsUseCase,
    private val getPlayerChapterInfoUseCase: GetPlayerChapterInfoUseCase,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        private const val TAG = "ChapterDetailsViewModel"
    }

    private val bookId: String = checkNotNull(savedStateHandle["bookId"])
    private val chapterId: String = checkNotNull(savedStateHandle["chapterId"])

    private val _uiState = MutableStateFlow(ChapterDetailsUiState())
    val uiState = _uiState.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    init {
        _uiState.update { it.copy(chapterTitle = formatChapterIdToTitle(chapterId)) }
        loadDetails()
    }

    private fun loadDetails() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val detailsResult = getChapterDetailsUseCase(bookId, chapterId)
            val mediaInfoResult = getPlayerChapterInfoUseCase(bookId, chapterId)

            detailsResult
                .onSuccess { detailsModel ->
                    mediaInfoResult
                        .onSuccess { mediaInfo ->
                            try {
                                val scenarioModel = mergeScenarios(detailsModel, mediaInfo)
                                _uiState.update {
                                    it.copy(
                                        isLoading = false,
                                        details = detailsModel.toUiModel(scenarioModel),
                                        error = null
                                    )
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to parse/merge scenarios", e)
                                _uiState.update {
                                    it.copy(
                                        isLoading = false,
                                        error = "Ошибка парсинга сценария: ${e.message}"
                                    )
                                }
                            }
                        }
                        .onFailure { mediaError ->
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    error = "Ошибка загрузки медиа: ${mediaError.message}"
                                )
                            }
                        }
                }
                .onFailure { detailsError ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Ошибка загрузки главы: ${detailsError.message}"
                        )
                    }
                }
        }
    }

    private fun mergeScenarios(
        detailsModel: ChapterDetails,
        mediaInfo: PlayerChapterInfo
    ): List<UiScenarioEntry> {
        val subtitlesPath = mediaInfo.media.subtitlesPath
            ?: throw Exception("Файл сценария (subtitles.json) не найден.")

        val subtitlesJson = File(subtitlesPath).readText()
        // Загружаем timingsList
        val timingsList = json.decodeFromString<List<SubtitleEntry>>(subtitlesJson)
        Log.d(TAG, "mergeScenarios: timingsList loaded. Size: ${timingsList.size}")

        // Загружаем domainScenario
        val domainScenario: List<ScenarioEntry> = detailsModel.scenario
        Log.d(TAG, "mergeScenarios: domainScenario loaded. Size: ${domainScenario.size}")

        // Создаем speakerMap, используя UUID.toString() как ключ
        val speakerMap = domainScenario.associateBy(
            keySelector = { it.id.toString() },
            valueTransform = { it.speaker }
        )
        Log.d(TAG, "mergeScenarios: speakerMap created. Size: ${speakerMap.size}")
        if (speakerMap.isNotEmpty()) {
            Log.d(TAG, "mergeScenarios: speakerMap keys: ${speakerMap.keys.take(5).joinToString()}")
        }


        // Итерируем timingsList и "обогащаем" его
        return timingsList.map { timingEntry ->
            // "b93d026a-79a9-45d6-979f-19488b02b2e8.wav" -> "b93d026a-79a9-45d6-979f-19488b02b2e8"
            val key = timingEntry.audioFile.removeSuffix(".wav")
            val speaker = speakerMap[key] ?: "Рассказчик"

            if (timingsList.first() == timingEntry) {
                Log.d(
                    TAG,
                    "mergeScenarios: Mapping first entry. Key: '$key', Speaker found: '$speaker'"
                )
            }

            UiScenarioEntry(
                id = timingEntry.audioFile,
                speaker = speaker,
                text = timingEntry.text,
                startMs = timingEntry.startMs,
                endMs = timingEntry.endMs,
                words = timingEntry.words
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

// --- Mapper ---

private fun ChapterDetails.toUiModel(mergedScenario: List<UiScenarioEntry>): UiChapterDetails {
    return UiChapterDetails(
        teaser = this.summary?.teaser ?: "Тизер недоступен",
        synopsis = this.summary?.synopsis ?: "Синопсис недоступен",
        scenario = mergedScenario,
        originalText = this.originalText
    )
}

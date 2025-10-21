package com.lapcevichme.bookweaver.presentation.ui.chapterdetails

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.bookweaver.domain.model.ChapterDetails
import com.lapcevichme.bookweaver.domain.usecase.books.GetChapterDetailsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// --- UI Models and State ---

data class UiScenarioEntry(
    val id: String,
    val speaker: String,
    val text: String
)

data class UiChapterDetails(
    val teaser: String,
    val synopsis: String,
    val scenario: List<UiScenarioEntry>,
    val originalText: String
)

data class ChapterDetailsUiState(
    val isLoading: Boolean = true,
    val chapterTitle: String = "Загрузка...",
    val details: UiChapterDetails? = null,
    val error: String? = null
)

// --- ViewModel ---

@HiltViewModel
class ChapterDetailsViewModel @Inject constructor(
    private val getChapterDetailsUseCase: GetChapterDetailsUseCase,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val bookId: String = checkNotNull(savedStateHandle["bookId"])
    private val chapterId: String = checkNotNull(savedStateHandle["chapterId"])

    private val _uiState = MutableStateFlow(ChapterDetailsUiState())
    val uiState = _uiState.asStateFlow()

    init {
        _uiState.update { it.copy(chapterTitle = formatChapterIdToTitle(chapterId)) }
        loadDetails()
    }

    private fun loadDetails() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            getChapterDetailsUseCase(bookId, chapterId)
                .onSuccess { details ->
                    _uiState.update {
                        it.copy(isLoading = false, details = details.toUiModel())
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message)
                    }
                }
        }
    }

    private fun formatChapterIdToTitle(chapterId: String): String {
        return try {
            val parts = chapterId.split("_")
            "Том ${parts[1]}, Глава ${parts[3]}"
        } catch (e: Exception) {
            chapterId // Fallback to raw id if parsing fails
        }
    }
}

// --- Mapper ---

private fun ChapterDetails.toUiModel(): UiChapterDetails {
    return UiChapterDetails(
        teaser = this.summary?.teaser ?: "Тизер недоступен",
        synopsis = this.summary?.synopsis ?: "Синопсис недоступен",
        scenario = this.scenario.map {
            UiScenarioEntry(
                id = it.id.toString(),
                speaker = it.speaker,
                text = it.text
            )
        },
        originalText = this.originalText
    )
}

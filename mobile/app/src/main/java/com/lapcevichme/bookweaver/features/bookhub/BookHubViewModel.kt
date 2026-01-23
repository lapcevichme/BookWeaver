package com.lapcevichme.bookweaver.features.bookhub

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.bookweaver.domain.model.DownloadProgress
import com.lapcevichme.bookweaver.domain.model.DownloadState
import com.lapcevichme.bookweaver.domain.usecase.books.DownloadChapterUseCase
import com.lapcevichme.bookweaver.domain.usecase.books.GetActiveBookFlowUseCase
import com.lapcevichme.bookweaver.domain.usecase.books.GetBookDetailsUseCase
import com.lapcevichme.bookweaver.domain.usecase.player.GetActiveChapterFlowUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface BookHubEvent {
    data class OnDownloadChapterClick(val chapterId: String) : BookHubEvent
    data object OnRetry : BookHubEvent
}

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class BookHubViewModel @Inject constructor(
    private val getBookDetailsUseCase: GetBookDetailsUseCase,
    private val downloadChapterUseCase: DownloadChapterUseCase,
    getActiveBookFlowUseCase: GetActiveBookFlowUseCase,
    getActiveChapterFlowUseCase: GetActiveChapterFlowUseCase
) : ViewModel() {

    private sealed interface BookDetailsResult {
        data object Loading : BookDetailsResult
        data class Success(val details: UiBookDetails?) : BookDetailsResult
        data class Error(val throwable: Throwable) : BookDetailsResult
    }

    private val _retryTrigger = MutableStateFlow(0)

    private val bookDetailsResultFlow: Flow<BookDetailsResult> = combine(
        getActiveBookFlowUseCase(),
        _retryTrigger
    ) { activeBookId, _ -> activeBookId }
        .flatMapLatest { activeBookId ->
            if (activeBookId == null) {
                flowOf(BookDetailsResult.Success(null))
            } else {
                flow {
                    val result = getBookDetailsUseCase(activeBookId)
                    result.fold(
                        onSuccess = { emit(BookDetailsResult.Success(it.toUiBookDetails())) },
                        onFailure = { emit(BookDetailsResult.Error(it)) }
                    )
                }.onStart {
                    emit(BookDetailsResult.Loading)
                }
            }
        }
        .catch { e ->
            emit(BookDetailsResult.Error(e))
        }

    private val _downloadProgress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())

    val uiState: StateFlow<BookDetailsUiState> = combine(
        bookDetailsResultFlow,
        getActiveChapterFlowUseCase(),
        getActiveBookFlowUseCase(),
        _downloadProgress
    ) { bookDetailsResult, activeChapterId, activeBookId, downloadProgressMap ->

        when (bookDetailsResult) {
            is BookDetailsResult.Loading -> BookDetailsUiState(
                isLoading = true,
                bookId = activeBookId,
                activeChapterId = activeChapterId,
                bookDetails = null,
                error = null
            )

            is BookDetailsResult.Success -> {
                val patchedVolumes = bookDetailsResult.details?.volumes?.map { volume ->
                    volume.copy(
                        chapters = volume.chapters.map { chapter ->
                            val progress = downloadProgressMap[chapter.id]
                            if (progress is DownloadProgress.Downloading) {
                                chapter.copy(downloadState = DownloadState.DOWNLOADING)
                            } else {
                                chapter
                            }
                        }
                    )
                }

                BookDetailsUiState(
                    isLoading = false,
                    bookId = activeBookId,
                    bookDetails = bookDetailsResult.details?.copy(
                        volumes = patchedVolumes ?: emptyList()
                    ),
                    activeChapterId = activeChapterId,
                    error = if (activeBookId != null && bookDetailsResult.details == null) "Книга не найдена" else null
                )
            }

            is BookDetailsResult.Error -> BookDetailsUiState(
                isLoading = false,
                bookId = activeBookId,
                bookDetails = null,
                activeChapterId = activeChapterId,
                error = bookDetailsResult.throwable.message ?: "Неизвестная ошибка"
            )
        }
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = BookDetailsUiState(isLoading = true)
        )

    fun onEvent(event: BookHubEvent) {
        when (event) {
            is BookHubEvent.OnDownloadChapterClick -> {
                downloadChapter(event.chapterId)
            }

            is BookHubEvent.OnRetry -> {
                _retryTrigger.update { it + 1 }
            }
        }
    }

    private fun downloadChapter(chapterId: String) {
        val bookId = uiState.value.bookId
        if (bookId == null) {
            Log.e("BookHubViewModel", "Невозможно скачать главу, bookId is null")
            return
        }

        viewModelScope.launch {
            downloadChapterUseCase(bookId, chapterId)
                .collect { progress ->
                    _downloadProgress.update { currentMap ->
                        currentMap + (chapterId to progress)
                    }

                    if (progress !is DownloadProgress.Downloading) {
                        _downloadProgress.update { currentMap ->
                            currentMap - chapterId
                        }
                    }
                }
        }
    }
}
package com.lapcevichme.bookweaver.presentation.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.bookweaver.domain.model.UiBook
import com.lapcevichme.bookweaver.domain.model.toUiBook
import com.lapcevichme.bookweaver.domain.usecase.audio.GetAudioUriUseCase
import com.lapcevichme.bookweaver.domain.usecase.audio.RequestAudioUseCase
import com.lapcevichme.bookweaver.domain.usecase.books.GetBooksUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class LibraryUiState(
    val isLoading: Boolean = true,
    val books: List<UiBook> = emptyList(), // Ожидает List<UiBook>
    val readyToPlayAudioUri: Uri? = null
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    getBooksUseCase: GetBooksUseCase,
    getAudioUriUseCase: GetAudioUriUseCase,
    private val requestAudioUseCase: RequestAudioUseCase,
//    private val clearAudioUseCase: ClearAudioUseCase // Добавим этот use-case
) : ViewModel() {

    val uiState: StateFlow<LibraryUiState> = combine(
        getBooksUseCase(), // Вызываем use-кейс как функцию
        getAudioUriUseCase(), // И этот тоже
    ) { domainBooks, audioUri ->
        val uiBooks = domainBooks.map { it.toUiBook() }
        LibraryUiState(
            isLoading = uiBooks.isEmpty(),
            books = uiBooks,
            readyToPlayAudioUri = audioUri
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = LibraryUiState()
    )


    fun onBookClicked(book: UiBook) {
        // Здесь используется filePath, который мы должны добавить в UiBook
        // Предположим, что book.id и есть filePath
        requestAudioUseCase(book.id)
    }

    fun onAudioHandled() {
//        clearAudioUseCase()
    }
}


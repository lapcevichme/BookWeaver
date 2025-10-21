package com.lapcevichme.bookweaver.presentation.ui.book.bookinstall

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.bookweaver.domain.usecase.books.DownloadAndInstallBookUseCase
import com.lapcevichme.bookweaver.domain.usecase.books.InstallBookUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject


// UI State: Содержит все данные, необходимые для отрисовки экрана
data class BookInstallationUiState(
    val isLoading: Boolean = false,
    val urlInput: String = "",
    val installationResult: Result<Unit>? = null
)

// Events: Определяет все возможные действия пользователя на экране
sealed class InstallationEvent {
    data class UrlChanged(val url: String) : InstallationEvent()
    object InstallFromUrlClicked : InstallationEvent()
    data class InstallFromFile(val uri: Uri?) : InstallationEvent()
    object ResultHandled : InstallationEvent()
}


@HiltViewModel
class BookInstallationViewModel @Inject constructor(
    private val downloadAndInstallBookUseCase: DownloadAndInstallBookUseCase,
    private val installBookUseCase: InstallBookUseCase,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(BookInstallationUiState())
    val uiState = _uiState.asStateFlow()

    // Обработчик всех событий с UI
    fun onEvent(event: InstallationEvent) {
        when (event) {
            is InstallationEvent.UrlChanged -> _uiState.update { it.copy(urlInput = event.url) }
            InstallationEvent.InstallFromUrlClicked -> installFromUrl()
            is InstallationEvent.InstallFromFile -> installFromUri(event.uri)
            InstallationEvent.ResultHandled -> _uiState.update { it.copy(installationResult = null) }
        }
    }

    private fun installFromUrl() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, installationResult = null) }
            val result = downloadAndInstallBookUseCase(_uiState.value.urlInput)
            _uiState.update {
                it.copy(isLoading = false, installationResult = result.map {})
            }
        }
    }

    private fun installFromUri(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, installationResult = null) }
            try {
                // Преобразование Uri -> InputStream происходит здесь, в presentation слое
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val result = installBookUseCase(inputStream)
                    _uiState.update {
                        it.copy(isLoading = false, installationResult = result.map {})
                    }
                } ?: throw Exception("Не удалось открыть файл")
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, installationResult = Result.failure(e))
                }
            }
        }
    }
}


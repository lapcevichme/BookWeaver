package com.lapcevichme.bookweaver.features.bookinstall

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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.lapcevichme.bookweaver.domain.model.DownloadProgress


@HiltViewModel
class BookInstallationViewModel @Inject constructor(
    private val downloadAndInstallBookUseCase: DownloadAndInstallBookUseCase,
    private val installBookUseCase: InstallBookUseCase,
    @param:ApplicationContext private val context: Context
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
            downloadAndInstallBookUseCase(_uiState.value.urlInput)
                .catch { e ->
                    _uiState.update {
                        it.copy(
                            installationResult = Result.failure(e),
                            downloadProgress = DownloadProgress.Idle
                        )
                    }
                }
                .onCompletion {
                    if (_uiState.value.isBusy) {
                        _uiState.update { it.copy(downloadProgress = DownloadProgress.Idle) }
                    }
                }
                .collect { progress ->
                    when (progress) {
                        is DownloadProgress.Downloading -> {
                            _uiState.update { it.copy(downloadProgress = progress) }
                        }
                        DownloadProgress.Installing -> {
                            _uiState.update { it.copy(downloadProgress = progress) }
                        }
                        // 'Idle' не должен приходить из UseCase, но на всякий случай
                        DownloadProgress.Idle -> {
                            _uiState.update { it.copy(downloadProgress = DownloadProgress.Idle) }
                        }
                    }
                }

            if (_uiState.value.installationResult == null) {
                _uiState.update {
                    it.copy(installationResult = Result.success(Unit))
                }
            }
        }
    }

    private fun installFromUri(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            // Установка из файла - это быстрый процесс,
            // поэтому мы просто покажем "Установка..."
            _uiState.update {
                it.copy(
                    downloadProgress = DownloadProgress.Installing,
                    installationResult = null
                )
            }
            try {
                // Преобразование Uri -> InputStream происходит здесь, в presentation слое
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val result = installBookUseCase(inputStream)
                    _uiState.update {
                        it.copy(
                            downloadProgress = DownloadProgress.Idle,
                            installationResult = result.map {}
                        )
                    }
                } ?: throw Exception("Не удалось открыть файл")
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        downloadProgress = DownloadProgress.Idle,
                        installationResult = Result.failure(e)
                    )
                }
            }
        }
    }
}
package com.lapcevichme.bookweaver.features.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.bookweaver.domain.model.ServerConnection
import com.lapcevichme.bookweaver.domain.repository.ServerRepository
import com.lapcevichme.bookweaver.domain.usecase.connection.HandleQrCodeUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConnectionUiState(
    val currentConnection: ServerConnection? = null,
    val isLoading: Boolean = false,
    val lastError: String? = null,
    val connectionSuccess: Boolean = false
)

@HiltViewModel
class ConnectionViewModel @Inject constructor(
    private val handleQrCodeUseCase: HandleQrCodeUseCase,
    private val serverRepository: ServerRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConnectionUiState(isLoading = true))
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            serverRepository.getServerConnection().collect { connection ->
                _uiState.update { it.copy(isLoading = false, currentConnection = connection) }
            }
        }
    }

    /**
     * Вызывается, когда сканер распознал QR-код.
     */
    fun onQrCodeScanned(qrContent: String) {
        _uiState.update { it.copy(isLoading = true, lastError = null) }

        viewModelScope.launch {
            val result = handleQrCodeUseCase(qrContent)
            if (result.isSuccess) {
                _uiState.update {
                    it.copy(isLoading = false, connectionSuccess = true, lastError = null)
                }
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        connectionSuccess = false,
                        lastError = result.exceptionOrNull()?.message ?: "Неизвестная ошибка"
                    )
                }
            }
        }
    }

    /**
     * "Забыть" текущий сервер.
     */
    fun onDisconnect() {
        viewModelScope.launch {
            serverRepository.clearServerConnection()
            _uiState.update { it.copy(connectionSuccess = false, lastError = null) }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(lastError = null) }
    }
}
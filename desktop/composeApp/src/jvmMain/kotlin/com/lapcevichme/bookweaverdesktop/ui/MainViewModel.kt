package com.lapcevichme.bookweaverdesktop.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.bookweaverdesktop.backend.BackendProcessManager
import com.lapcevichme.bookweaverdesktop.model.ServerState
import com.lapcevichme.bookweaverdesktop.server.ServerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Глобальная ViewModel, отвечающая за жизненный цикл всего приложения.
 * Управляет фоновыми процессами (Python бэкенд, WebSocket сервер).
 */
class MainViewModel(
    private val serverManager: ServerManager,
    private val backendProcessManager: BackendProcessManager,
) : ViewModel() {

    // --- Состояния ---
    val webSocketServerState: StateFlow<ServerState> = serverManager.serverState
    val backendState: StateFlow<BackendProcessManager.State> = backendProcessManager.state
    val backendLogs: StateFlow<List<String>> = backendProcessManager.logs

    init {
        // Запускаем оба сервиса при старте
        startBackend()
        startWebSocketServer()
    }

    // --- Управление WebSocket-сервером ---
    fun startWebSocketServer() {
        if (webSocketServerState.value is ServerState.Disconnected) {
            viewModelScope.launch(Dispatchers.IO) {
                serverManager.start()
            }
        }
    }

    fun showConnectionInstructions() {
        if (webSocketServerState.value is ServerState.ReadyForConnection) {
            serverManager.showConnectionInstructions()
        }
    }

    // --- Управление Python Backend Process ---
    fun startBackend() {
        if (backendState.value == BackendProcessManager.State.STOPPED || backendState.value is BackendProcessManager.State.FAILED) {
            viewModelScope.launch(Dispatchers.IO) {
                backendProcessManager.start()
            }
        }
    }

    fun stopBackend() {
        // Остановка процесса тоже может занять время, лучше делать в фоне.
        viewModelScope.launch(Dispatchers.IO) {
            backendProcessManager.stop()
        }
    }

    // --- Жизненный цикл ---
    suspend fun onAppClose() {
        serverManager.stop()
        backendProcessManager.stop()
    }
}


package com.lapcevichme.bookweaverdesktop

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.lapcevichme.bookweaverdesktop.di.AppContainer
import com.lapcevichme.bookweaverdesktop.ui.App
import com.lapcevichme.bookweaverdesktop.ui.MainViewModel

fun main() = application {
    val appContainer = AppContainer()
    val viewModel = MainViewModel(
        serverManager = appContainer.serverManager,
        backendProcessManager = appContainer.backendProcessManager,
        apiClient = appContainer.apiClient,
        configManager = appContainer.configManager
    )

    // Запускаем WebSocket-сервер при старте приложения
    LaunchedEffect(Unit) {
        viewModel.startWebSocketServer()
    }

    Window(
        onCloseRequest = {
            // При закрытии окна останавливаем сервера
            viewModel.onAppClose()
            exitApplication()
        },
        title = "BookWeaver Desktop"
    ) {
        MaterialTheme {
            App(viewModel)
        }
    }
}

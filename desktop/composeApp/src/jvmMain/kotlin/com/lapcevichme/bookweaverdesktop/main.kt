package com.lapcevichme.bookweaverdesktop

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.lapcevichme.bookweaverdesktop.di.AppContainer
import com.lapcevichme.bookweaverdesktop.ui.App
import com.lapcevichme.bookweaverdesktop.ui.MainViewModel
import kotlinx.coroutines.runBlocking

fun main() = application {

    val appContainer = AppContainer()
    val viewModel = MainViewModel(
        serverManager = appContainer.serverManager,
        backendProcessManager = appContainer.backendProcessManager,
        apiClient = appContainer.apiClient,
        configManager = appContainer.configManager,
        bookManager = appContainer.bookManager
    )

    // Регистрируем хук завершения работы
    // Этот код выполнится, когда приложение будет закрываться,
    // гарантируя корректную остановку всех сервисов.
    Runtime.getRuntime().addShutdownHook(Thread {
        println("Shutdown hook triggered. Stopping services...")
        // Используем runBlocking здесь, потому что мы в обычном потоке,
        // а нам нужно вызвать suspend-функцию и дождаться ее.
        runBlocking {
            viewModel.onAppClose()
        }
        println("Services stopped. Exiting.")
    })


    // Запускаем WebSocket-сервер при старте приложения
    LaunchedEffect(Unit) {
        viewModel.startWebSocketServer()
    }

    Window(
        onCloseRequest = {
            exitApplication()
        },
        title = "BookWeaver Desktop"
    ) {
        MaterialTheme {
            App(viewModel)
        }
    }
}

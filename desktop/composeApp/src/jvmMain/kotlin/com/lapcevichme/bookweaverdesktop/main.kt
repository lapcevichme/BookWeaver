package com.lapcevichme.bookweaverdesktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.lapcevichme.bookweaverdesktop.core.di.initKoin
import com.lapcevichme.bookweaverdesktop.core.navigation.BookWeaverNavHost
import com.lapcevichme.bookweaverdesktop.ui.MainViewModel
import kotlinx.coroutines.runBlocking
import org.koin.compose.KoinApplication
import org.koin.compose.koinInject

fun main() = application {
    // KoinApplication - это обертка, которая инициализирует Koin
    // и предоставляет DI-контейнер всему приложению.
    KoinApplication(application = {
        initKoin()
    }) {
        // Получаем MainViewModel из Koin. Он будет жить в течение всего приложения.
        val viewModel = koinInject<MainViewModel>()

        // Хук для корректного завершения работы приложения.
        // Он гарантирует, что все фоновые процессы (Python, WebSocket) будут остановлены.
        Runtime.getRuntime().addShutdownHook(Thread {
            println("Shutdown hook triggered. Stopping services...")
            runBlocking {
                viewModel.onAppClose()
            }
            println("Services stopped. Exiting.")
        })


        Window(
            onCloseRequest = ::exitApplication,
            title = "BookWeaver"
        ) {
            BookWeaverNavHost(window = this.window)
        }
    }
}
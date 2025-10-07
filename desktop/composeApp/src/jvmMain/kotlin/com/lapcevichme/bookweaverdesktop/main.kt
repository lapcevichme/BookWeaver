package com.lapcevichme.bookweaverdesktop

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.lapcevichme.bookweaverdesktop.server.ServerManager
import com.lapcevichme.bookweaverdesktop.ui.App

fun main() = application {
    val serverManager = ServerManager
    LaunchedEffect(Unit) {
        serverManager.start()
    }
    Window(
        onCloseRequest = {
            serverManager.stop()
            exitApplication()
        },
        title = "BookWeaver Desktop"
    ) {
        MaterialTheme {
            App(serverManager)
        }
    }
}
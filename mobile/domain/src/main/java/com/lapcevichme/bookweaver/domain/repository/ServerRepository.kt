package com.lapcevichme.bookweaver.domain.repository

import kotlinx.coroutines.flow.StateFlow
import okio.ByteString

interface ServerRepository {
    // Потоки данных от сервера
    val connectionStatus: StateFlow<String>
    val logs: StateFlow<List<String>>
    val incomingMessages: StateFlow<String>
    val incomingBytes: StateFlow<ByteString>

    // Действия
    fun findAndConnectToServer(fingerprint: String)
    fun sendMessage(message: String)
    fun disconnect()
}

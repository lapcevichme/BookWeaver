package com.lapcevichme.bookweaver.domain.model

/**
 * Хранит данные для подключения к self-hosted серверу.
 */
data class ServerConnection(
    val host: String, // e.g., "http://192.168.1.10:8080"
    val token: String // e.g., "a1b2c3d4e5f6..."
)
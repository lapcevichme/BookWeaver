package com.lapcevichme.network

import com.lapcevichme.bookweaver.domain.repository.IServerRepository
import kotlinx.coroutines.flow.StateFlow
import okio.ByteString
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Финальная, "глупая" версия репозитория.
 * Предоставляет только базовые функции для работы с сетью.
 * Ничего не знает о книгах, аудио или бизнес-логике приложения.
 */
@Singleton
class ServerRepository @Inject constructor(
    private val nsdHelper: NsdHelper,
    private val webSocketClient: WebSocketClient
) : IServerRepository {
    // --- ПУБЛИЧНОЕ API БИБЛИОТЕКИ ---

    // Статус соединения
    override val connectionStatus: StateFlow<String> = webSocketClient.connectionStatus
    override val logs: StateFlow<List<String>> = webSocketClient.logs
    override val incomingMessages: StateFlow<String> = webSocketClient.incomingMessages as StateFlow<String>
    override val incomingBytes: StateFlow<ByteString> = webSocketClient.incomingBytes as StateFlow<ByteString>

    /**
     * Начать поиск и подключение к серверу.
     */
    override fun findAndConnectToServer(fingerprint: String) {
        nsdHelper.discoverServices(
            serviceType = "_bookweaver._tcp.",
            onServiceFound = { serviceInfo ->
                val host = serviceInfo.host.hostAddress ?: return@discoverServices
                webSocketClient.connect(host, serviceInfo.port, fingerprint)
            },
            onDiscoveryStopped = {
                // ...
            }
        )
    }

    /**
     * Отправить универсальное текстовое сообщение на сервер.
     */
    override fun sendMessage(message: String) {
        webSocketClient.sendMessage(message)
    }

    /**
     * Разорвать соединение.
     */
    override fun disconnect() {
        webSocketClient.disconnect()
    }
}


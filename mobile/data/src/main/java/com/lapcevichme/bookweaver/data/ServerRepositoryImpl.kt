package com.lapcevichme.bookweaver.data

import com.lapcevichme.bookweaver.domain.repository.ServerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import okio.ByteString
import javax.inject.Inject
import javax.inject.Singleton


/**
 * Финальная, "глупая" версия репозитория.
 * Предоставляет только базовые функции для работы с сетью.
 * Ничего не знает о книгах, аудио или бизнес-логике приложения.
 */
@Singleton
class ServerRepositoryImpl @Inject constructor(
    private val nsdHelper: NsdHelper,
    private val webSocketClient: WebSocketClient
) : ServerRepository {
    // --- ПУБЛИЧНОЕ API БИБЛИОТЕКИ ---
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Статус соединения
    override val connectionStatus: StateFlow<String> = webSocketClient.connectionStatus
    override val logs: StateFlow<List<String>> = webSocketClient.logs
    override val incomingMessages: StateFlow<String> = webSocketClient.incomingMessages.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = "" // или другое начальное значение
    )
    override val incomingBytes: StateFlow<ByteString> = webSocketClient.incomingBytes.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ByteString.EMPTY
    )

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
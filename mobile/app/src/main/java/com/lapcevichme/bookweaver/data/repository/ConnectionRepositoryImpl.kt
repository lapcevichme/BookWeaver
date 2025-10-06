package com.lapcevichme.bookweaver.data.repository

import android.app.Application
import android.content.Context
import com.lapcevichme.bookweaver.domain.repository.ConnectionRepository
import com.lapcevichme.network.ServerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class ConnectionRepositoryImpl @Inject constructor(
    private val serverRepository: ServerRepository,
    application: Application
) : ConnectionRepository {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs = application.getSharedPreferences("server_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val FINGERPRINT_KEY = "server_fingerprint"
        private const val RETRY_DELAY = 5000L // 5 секунд
    }

    override fun start() {
        // Запускаем переподключение при старте приложения
        reconnectIfPossible()
        // Начинаем следить за статусом для будущих переподключений
        observeConnectionStatus()
    }

    private fun observeConnectionStatus() {
        serverRepository.connectionStatus
            .filter { it == "Ошибка соединения" || it == "Соединение закрыто" }
            .onEach {
                delay(RETRY_DELAY)
                reconnectIfPossible()
            }
            .launchIn(scope)
    }

    private fun reconnectIfPossible() {
        val fingerprint = prefs.getString(FINGERPRINT_KEY, null)
        if (fingerprint != null) {
            serverRepository.findAndConnectToServer(fingerprint)
        }
    }
}

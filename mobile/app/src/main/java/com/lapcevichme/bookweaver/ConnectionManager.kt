package com.lapcevichme.bookweaver

import android.app.Application
import android.content.Intent
import com.lapcevichme.bookweaver.service.ConnectionService
import com.lapcevichme.network.ServerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectionManager @Inject constructor(
    private val application: Application,
    private val serverRepository: ServerRepository
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        observeConnectionStatus()
    }

    private fun observeConnectionStatus() {
        scope.launch {
            serverRepository.connectionStatus.collectLatest { status ->
                if (status.startsWith("Подключено")) {
                    startService(status)
                } else if (status == "Получение аудио...") {
                    updateServiceStatus(status)
                } else {
                    stopService()
                }
            }
        }
    }

    private fun startService(status: String) {
        val intent = Intent(application, ConnectionService::class.java).apply {
            action = ConnectionService.ACTION_START
            putExtra(ConnectionService.EXTRA_STATUS, status)
        }
        application.startService(intent)
    }

    private fun updateServiceStatus(status: String) {
        val intent = Intent(application, ConnectionService::class.java).apply {
            action = ConnectionService.ACTION_UPDATE_STATUS
            putExtra(ConnectionService.EXTRA_STATUS, status)
        }
        application.startService(intent)
    }

    private fun stopService() {
        val intent = Intent(application, ConnectionService::class.java).apply {
            action = ConnectionService.ACTION_STOP
        }
        application.startService(intent)
    }
}


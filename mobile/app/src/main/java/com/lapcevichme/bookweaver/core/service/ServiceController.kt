package com.lapcevichme.bookweaver.core.service

import android.app.Application
import android.content.Intent
import com.lapcevichme.bookweaver.domain.usecase.connection.ManageConnectionStatusUseCase
import com.lapcevichme.bookweaver.domain.usecase.connection.ServiceAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServiceController @Inject constructor(
    private val application: Application,
    private val manageConnectionStatusUseCase: ManageConnectionStatusUseCase // Инжектируем UseCase
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        observeServiceActions()
    }

    private fun observeServiceActions() {
        scope.launch {
            // Просто слушаем команды от UseCase и выполняем их
            manageConnectionStatusUseCase.getServiceActions().collectLatest { action ->
                when (action) {
                    is ServiceAction.Start -> startService(action.status)
                    is ServiceAction.Update -> updateServiceStatus(action.status)
                    is ServiceAction.Stop -> stopService()
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

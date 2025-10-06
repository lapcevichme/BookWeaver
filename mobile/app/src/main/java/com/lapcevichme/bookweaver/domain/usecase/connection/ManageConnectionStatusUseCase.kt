package com.lapcevichme.bookweaver.domain.usecase.connection

import com.lapcevichme.network.ServerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class ManageConnectionStatusUseCase @Inject constructor(
    private val serverRepository: ServerRepository // Зависим от интерфейса!
) {
    // Этот Flow будет сообщать, что нужно сделать с сервисом
    fun getServiceActions(): Flow<ServiceAction> = serverRepository.connectionStatus
        .map { status ->
            // Здесь чистая бизнес-логика, без Android-кода
            when {
                status.startsWith("Подключено") -> ServiceAction.Start(status)
                status == "Получение аудио..." -> ServiceAction.Update(status)
                else -> ServiceAction.Stop
            }
        }
}

// Вспомогательный класс для описания действий
sealed class ServiceAction {
    data class Start(val status: String) : ServiceAction()
    data class Update(val status: String) : ServiceAction()
    object Stop : ServiceAction()
}

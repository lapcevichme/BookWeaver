package com.lapcevichme.bookweaver.domain.usecase.connection

import com.lapcevichme.bookweaver.domain.repository.SettingsRepository
import com.lapcevichme.network.ConnectionInfo
import com.lapcevichme.network.ServerRepository
import kotlinx.serialization.json.Json
import javax.inject.Inject

class HandleQrCodeUseCase @Inject constructor(
    private val serverRepository: ServerRepository,
    // Нам нужен способ сохранять fingerprint. Создадим для этого новый репозиторий.
    private val settingsRepository: SettingsRepository
) {
    private val json = Json { ignoreUnknownKeys = true }

    operator fun invoke(qrCodeContent: String?) {
        if (qrCodeContent.isNullOrBlank()) return
        try {
            val info = json.decodeFromString(ConnectionInfo.serializer(), qrCodeContent)
            // 1. Сохраняем отпечаток через репозиторий
            settingsRepository.saveServerFingerprint(info.fingerprint)
            // 2. Запускаем подключение
            serverRepository.findAndConnectToServer(info.fingerprint)
        } catch (e: Exception) {
            // Можно обработать ошибку, например, обновив некий StateFlow
            println("Error parsing QR code: ${e.message}")
        }
    }
}

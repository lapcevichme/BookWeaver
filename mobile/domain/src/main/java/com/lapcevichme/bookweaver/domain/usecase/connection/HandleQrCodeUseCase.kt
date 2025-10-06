package com.lapcevichme.bookweaver.domain.usecase.connection

import com.lapcevichme.bookweaver.domain.repository.ServerRepository
import com.lapcevichme.bookweaver.domain.repository.SettingsRepository
import javax.inject.Inject

class HandleQrCodeUseCase @Inject constructor(
    private val serverRepository: ServerRepository,
    private val settingsRepository: SettingsRepository
) {
    operator fun invoke(fingerprint: String) {
        if (fingerprint.isBlank()) return

        settingsRepository.saveServerFingerprint(fingerprint)
        serverRepository.findAndConnectToServer(fingerprint)
    }
}
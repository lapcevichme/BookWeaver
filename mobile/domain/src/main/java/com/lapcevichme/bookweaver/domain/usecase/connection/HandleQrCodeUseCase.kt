package com.lapcevichme.bookweaver.domain.usecase.connection

import com.lapcevichme.bookweaver.domain.repository.ServerRepository
import com.lapcevichme.bookweaver.domain.repository.SettingsRepository
import javax.inject.Inject

class HandleQrCodeUseCase @Inject constructor(
    private val serverRepository: ServerRepository,
    private val settingsRepository: SettingsRepository
) {
    // Используем Result для явной обработки ошибок в ViewModel
    operator fun invoke(qrCodeContent: String): Result<Unit> {
        if (!isValidSha256Fingerprint(qrCodeContent)) {
            // Если QR-код неверный, возвращаем ошибку
            return Result.failure(IllegalArgumentException("Invalid QR code format"))
        }

        settingsRepository.saveServerFingerprint(qrCodeContent)
        serverRepository.findAndConnectToServer(qrCodeContent)
        return Result.success(Unit)
    }

    /**
     * Проверяет, что строка соответствует формату отпечатка SHA-256.
     * Формат: "SHA-256;" + 32 пары шестнадцатеричных символов через двоеточие.
     * Пример: SHA-256;AB:12:CD:34:...
     */
    private fun isValidSha256Fingerprint(fingerprint: String): Boolean {
        // Регулярное выражение для точной проверки формата
        val fingerprintRegex = "^SHA-256;([0-9A-F]{2}:){31}[0-9A-F]{2}$".toRegex(RegexOption.IGNORE_CASE)
        return fingerprint.matches(fingerprintRegex)
    }
}

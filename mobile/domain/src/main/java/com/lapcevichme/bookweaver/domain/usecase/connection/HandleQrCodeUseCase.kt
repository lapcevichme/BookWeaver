package com.lapcevichme.bookweaver.domain.usecase.connection

import com.lapcevichme.bookweaver.domain.repository.ServerRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * Внутренняя модель данных для парсинга информации из QR-кода.
 * Используется только внутри [HandleQrCodeUseCase].
 */
@Serializable
private data class QrCodeData(
    val ip: String,
    val port: Int,
    val token: String,
    val serverName: String?
)

/**
 * Use-case для обработки данных, полученных из отсканированного QR-кода.
 * Он парсит JSON-строку, валидирует данные и сохраняет информацию о подключении
 * в [ServerRepository].
 */
class HandleQrCodeUseCase @Inject constructor(
    private val serverRepository: ServerRepository,
    private val json: Json
) {
    /**
     * Выполняет use-case.
     *
     * @param qrContent Содержимое QR-кода в виде JSON-строки.
     * @return [Result.success] в случае успешного сохранения данных,
     *         [Result.failure] с исключением в случае ошибки.
     */
    suspend operator fun invoke(qrContent: String): Result<Unit> {
        return try {
            // Десериализация JSON из строки в объект QrCodeData
            val qrData = json.decodeFromString<QrCodeData>(qrContent)

            // Базовая валидация полученных данных
            if (qrData.ip.isBlank() || qrData.token.isBlank()) {
                throw IllegalArgumentException("IP-адрес или токен в QR-коде не могут быть пустыми.")
            }

            // Формирование полного URL-адреса хоста
            // TODO: http или https?
            val host = "http://${qrData.ip}:${qrData.port}"

            // Сохранение данных о подключении через репозиторий
            serverRepository.saveServerConnection(host, qrData.token)

            Result.success(Unit)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }
}

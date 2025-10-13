package com.lapcevichme.bookweaverdesktop.server

import com.lapcevichme.bookweaverdesktop.backend.BookManager
import com.lapcevichme.bookweaverdesktop.model.Book
import com.lapcevichme.bookweaverdesktop.model.WsBookList
import com.lapcevichme.bookweaverdesktop.model.WsMessage
import com.lapcevichme.bookweaverdesktop.model.WsRequestAudio
import com.lapcevichme.bookweaverdesktop.model.WsRequestBookList
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.origin
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.io.File
import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}
private const val BUFFER_SIZE = 8192

fun Application.configureKtorApp(serverManager: ServerManager, bookManager: BookManager) {
    install(ContentNegotiation) {
        json(serverManager.json)
    }
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 15.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }
    routing {
        get("/") {
            call.respondText("WSS server is running.")
        }
        webSocket("/") {
            val remoteHost = call.request.origin.remoteHost
            if (!serverManager.peerSession.compareAndSet(null, this)) {
                logger.warn { "Attempted second connection from $remoteHost. Rejected." }
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Session is already active."))
                return@webSocket
            }
            serverManager.onPeerConnected(this, remoteHost)
            logger.info { "✅ Device connected: $remoteHost" }
            try {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val text = frame.readText()
                        try {
                            val message = serverManager.json.decodeFromString<WsMessage>(text)
                            when (message) {
                                is WsRequestAudio -> {
                                    launch(Dispatchers.IO) { handleAudioRequest(this@webSocket, message.filePath) }
                                }

                                is WsRequestBookList -> {
                                    logger.info { "Received request for book list from $remoteHost" }

                                    val result = bookManager.getProjectList()
                                    val response = WsBookList(
                                        books = result.getOrNull()?.map { Book(it, "", "") } ?: emptyList()
                                    )
                                    sendSerialized(serverManager.json, response)
                                }

                                else -> {
                                    logger.warn { "Received unknown or unhandled message type from $remoteHost" }
                                }
                            }
                        } catch (e: Exception) {
                            logger.error(e) { "WebSocket error from $remoteHost: Failed to parse message: $text" }
                        }
                    }
                }
            } catch (e: ClosedReceiveChannelException) {
                logger.info { "Connection with $remoteHost closed (client disconnected)." }
            } catch (e: Exception) {
                logger.error(e) { "WebSocket error from $remoteHost" }
            } finally {
                serverManager.peerSession.compareAndSet(this, null)
                serverManager.onPeerDisconnected()
                logger.info { "Device $remoteHost disconnected." }
            }
        }
    }
}

private suspend fun handleAudioRequest(session: WebSocketSession, requestedPath: String) {
    // ВАЖНО: Убедитесь, что директория для файлов существует и безопасна.
    // В реальном приложении путь нужно брать из настроек.
    val baseDir = File(System.getProperty("user.home"), "BookWeaver/shared").apply { mkdirs() }
    val requestedFile = File(baseDir, requestedPath).canonicalFile

    // ПРОВЕРКА БЕЗОПАСНОСТИ: Убеждаемся, что запрашиваемый файл находится внутри разрешенной директории.
    if (!requestedFile.startsWith(baseDir)) {
        logger.warn { "Path Traversal attempt blocked for path: $requestedPath" }
        // В идеале, отправить клиенту сообщение об ошибке.
        return
    }

    if (!requestedFile.exists() || !requestedFile.isFile) {
        logger.warn { "File not found: ${requestedFile.path}" }
        // Отправить клиенту сообщение, что файл не найден.
        return
    }

    try {
        val buffer = ByteArray(BUFFER_SIZE)
        requestedFile.inputStream().use { inputStream ->
            while (session.isActive) {
                val bytesRead = inputStream.read(buffer)
                if (bytesRead == -1) break
                session.send(Frame.Binary(true, buffer.copyOf(bytesRead)))
            }
        }
        // session.sendSerialized(WsAudioStreamEnd) // Сообщить клиенту, что передача завершена
    } catch (e: IOException) {
        logger.error(e) { "Error reading file '$requestedPath'" }
        // session.sendSerialized(WsAudioStreamError("File read error: ${e.message}"))
    }
}


private suspend fun WebSocketSession.sendSerialized(json: kotlinx.serialization.json.Json, message: WsMessage) {
    if (!isActive) return
    val jsonString = json.encodeToString(WsMessage.serializer(), message)
    send(Frame.Text(jsonString))
}

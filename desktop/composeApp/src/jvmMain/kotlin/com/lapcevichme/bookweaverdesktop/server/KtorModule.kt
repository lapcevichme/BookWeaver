package com.lapcevichme.bookweaverdesktop.server

import com.lapcevichme.bookweaverdesktop.backend.BookManager
import com.lapcevichme.bookweaverdesktop.model.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.contentnegotiation.*
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
import java.nio.file.Files
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
    val musicDir = File(System.getProperty("user.dir"), "shared_music").apply { if (!exists()) mkdirs() }
    val normalizedPath: Path
    try {
        normalizedPath = Paths.get(requestedPath).normalize()
    } catch (e: InvalidPathException) {
        logger.error { "Invalid path requested: '$requestedPath': ${e.message}" }
        // TODO: Переделать отправку ошибок на новый лад, если нужно
        // session.sendSerialized(WsAudioStreamError("Invalid file name: contains invalid characters."))
        return
    }
    if (normalizedPath.nameCount > 1 || normalizedPath.toString().contains("..")) {
        logger.warn { "❌ Error: Invalid path requested: '$requestedPath'. Contains separators or '..'." }
        // session.sendSerialized(WsAudioStreamError("Invalid file name: path must not contain separators or '..'."))
        return
    }
    try {
        val audioFile = File(musicDir, normalizedPath.toString())
        if (!audioFile.exists() || !audioFile.isFile || !Files.isSameFile(
                audioFile.toPath(),
                Paths.get(musicDir.path, normalizedPath.toString())
            )
        ) {
            logger.warn { "❌ Error: File not found or access denied: '$requestedPath'" }
            // session.sendSerialized(WsAudioStreamError("File not found or access denied: $requestedPath"))
            return
        }
        val buffer = ByteArray(BUFFER_SIZE)
        audioFile.inputStream().use { inputStream ->
            while (session.isActive) {
                val bytesRead = inputStream.read(buffer)
                if (bytesRead == -1) break
                session.send(Frame.Binary(true, buffer.copyOf(bytesRead)))
            }
        }
        // session.sendSerialized(WsAudioStreamEnd)
    } catch (e: IOException) {
        logger.error { "❌ Error: Failed to read file '$requestedPath': ${e.message}" }
        // session.sendSerialized(WsAudioStreamError("File read error: ${e.message}"))
    }
}

private suspend fun WebSocketSession.sendSerialized(json: kotlinx.serialization.json.Json, message: WsMessage) {
    if (!isActive) return
    val jsonString = json.encodeToString(WsMessage.serializer(), message)
    send(Frame.Text(jsonString))
}


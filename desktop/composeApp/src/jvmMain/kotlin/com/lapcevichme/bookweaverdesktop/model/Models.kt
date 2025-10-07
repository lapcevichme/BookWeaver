package com.lapcevichme.bookweaverdesktop.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class WsMessage

@Serializable
@SerialName("request_book_list")
object WsRequestBookList : WsMessage()

@Serializable
@SerialName("book_list")
data class WsBookList(
    val books: List<Book>
) : WsMessage()

@Serializable
data class Book(
    val title: String,
    val author: String,
    val filePath: String
)

@Serializable
@SerialName("signal")
data class WsSignal(val data: String) : WsMessage()

@Serializable
@SerialName("error")
data class WsError(val message: String) : WsMessage()

@Serializable
@SerialName("request_audio")
data class WsRequestAudio(val filePath: String) : WsMessage()

@Serializable
@SerialName("audio_stream_end")
object WsAudioStreamEnd : WsMessage()

@Serializable
@SerialName("audio_stream_error")
data class WsAudioStreamError(val error: String) : WsMessage()

@Serializable
data class ConnectionInfo(val ips: List<String>, val port: Int, val fingerprint: String)
sealed class ServerState {
    object Disconnected : ServerState()
    object ReadyForConnection : ServerState()
    data class AwaitingConnection(val qrCodeData: String) : ServerState()
    data class PeerConnected(val peerInfo: String) : ServerState()
    data class Error(val message: String) : ServerState()
}

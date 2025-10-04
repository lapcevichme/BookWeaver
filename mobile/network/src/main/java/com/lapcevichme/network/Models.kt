package com.lapcevichme.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Data models for network communication.
 * These must be identical to the models on the server-side application.
 */

@Serializable
sealed class WsMessage

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

// Data model contained in the QR code
@Serializable
data class ConnectionInfo(
    val ips: List<String>,
    val port: Int,
    val fingerprint: String
)

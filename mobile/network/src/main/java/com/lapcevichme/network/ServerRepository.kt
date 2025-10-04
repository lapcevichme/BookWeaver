package com.lapcevichme.network

import android.net.Uri
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single source of truth for network operations.
 * This is the public API of the :network module.
 * It hides the implementation details of NsdHelper and WebSocketClient.
 */
@Singleton
class ServerRepository @Inject constructor(
    private val nsdHelper: NsdHelper,
    private val webSocketClient: WebSocketClient
) {
    val connectionStatus: StateFlow<String> = webSocketClient.connectionStatus
    val receivedAudio: StateFlow<Uri?> = webSocketClient.audioFileUri
    val logs: StateFlow<List<String>> = webSocketClient.logs

    fun findAndConnectToServer(fingerprint: String) {
        nsdHelper.discoverServices(
            serviceType = "_bookweaver._tcp.",
            onServiceFound = { serviceInfo ->
                val host = serviceInfo.host.hostAddress ?: return@discoverServices
                webSocketClient.connect(host, serviceInfo.port, fingerprint)
            },
            onDiscoveryStopped = {
                if (connectionStatus.value == "Не подключено") {
                    // Optionally log that discovery stopped without finding anything
                }
            }
        )
    }

    fun requestAudioFile(fileName: String) {
        webSocketClient.requestAudioFile(fileName)
    }

    fun clearAudioFileUri() {
        webSocketClient.clearAudioFileUri()
    }

    fun disconnect() {
        webSocketClient.disconnect()
    }
}

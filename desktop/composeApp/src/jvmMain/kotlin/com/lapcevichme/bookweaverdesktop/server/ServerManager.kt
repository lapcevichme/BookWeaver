package com.lapcevichme.bookweaverdesktop.server

import com.lapcevichme.bookweaverdesktop.model.ConnectionInfo
import com.lapcevichme.bookweaverdesktop.model.ServerState
import com.lapcevichme.bookweaverdesktop.util.NetworkUtils
import com.lapcevichme.bookweaverdesktop.util.SecurityUtils
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.websocket.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import java.util.concurrent.atomic.AtomicReference
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

private val logger = KotlinLogging.logger {}

object ServerManager {
    private const val PORT = 8765
    internal const val KEYSTORE_FILE = "keystore.bks"
    private const val SERVICE_TYPE = "_bookweaver._tcp.local."
    private const val SERVICE_NAME = "BookWeaver Desktop Server"
    private const val KEY_ALIAS = "bookweaver"

    private val _serverState = MutableStateFlow<ServerState>(ServerState.Disconnected)
    val serverState = _serverState.asStateFlow()

    private var ktorServer: EmbeddedServer<*, *>? = null
    private var jmDnsInstances: List<JmDNS> = emptyList()
    private var fingerprint: String = ""

    internal val peerSession = AtomicReference<WebSocketSession?>()
    internal val json = Json { isLenient = true; ignoreUnknownKeys = true; classDiscriminator = "type" }

    fun start() {
        try {
            // Получаем keystore, fingerprint И пароль (CharArray)
            val (keyStore, calculatedFingerprint, keystorePassword) = SecurityUtils.setupCertificate()
            fingerprint = calculatedFingerprint

            ktorServer = embeddedServer(
                Netty,
                configure = {
                    sslConnector(
                        keyStore = keyStore,
                        keyAlias = KEY_ALIAS,
                        keyStorePassword = { keystorePassword.copyOf() },
                        privateKeyPassword = { keystorePassword.copyOf() }
                    ) {
                        port = PORT
                        host = "0.0.0.0"
                    }
                },
                module = {
                    configureKtorApp()
                }
            )
            ktorServer!!.start(wait = false)

            // Очищаем пароль из памяти для безопасности
            keystorePassword.fill('\u0000')

            setupJmDNS()
            _serverState.value = ServerState.ReadyForConnection
            logger.info { "✅ Server started. Certificate fingerprint: $fingerprint" }
        } catch (e: Exception) {
            logger.error(e) { "Error starting server" }
            _serverState.value = ServerState.Error(e.message ?: "Unknown error starting server")
        }
    }

    fun stop() {
        try {
            jmDnsInstances.forEach { it.close() }
            ktorServer?.stop(gracePeriodMillis = 1000L, timeoutMillis = 2000L)
            _serverState.value = ServerState.Disconnected
            logger.info { "🛑 Server stopped." }
        } catch (e: Exception) {
            logger.error(e) { "Error stopping server" }
        }
    }

    fun showConnectionInstructions() {
        if (peerSession.get()?.isActive == true) {
            logger.info { "Device already connected." }
            return
        }
        val ips = NetworkUtils.getAllLocalIPs().map { it.hostAddress }
        if (ips.isEmpty()) {
            _serverState.value = ServerState.Error("No active network interfaces found.")
            return
        }
        val connectionInfo = ConnectionInfo(ips = ips, port = PORT, fingerprint = fingerprint)
        val qrCodePayload = json.encodeToString(ConnectionInfo.serializer(), connectionInfo)
        _serverState.value = ServerState.AwaitingConnection(qrCodePayload)
    }

    fun onPeerConnected(session: WebSocketSession, remoteHost: String) {
        _serverState.value = ServerState.PeerConnected(remoteHost)
    }

    fun onPeerDisconnected() {
        // Если устройство отключается, возвращаемся в состояние готовности,
        // позволяя показать QR-код для нового спаривания, если потребуется.
        _serverState.value = ServerState.ReadyForConnection
    }

    private fun setupJmDNS() {
        val addresses = NetworkUtils.getAllLocalIPs()
        if (addresses.isEmpty()) {
            _serverState.value = ServerState.Error("No network interfaces found.")
            return
        }
        jmDnsInstances = addresses.map { addr ->
            JmDNS.create(addr).apply {
                val serviceInfo =
                    ServiceInfo.create(SERVICE_TYPE, SERVICE_NAME, PORT, "desc=BookWeaver audio streaming")
                registerService(serviceInfo)
            }
        }
        logger.info { "mDNS service registered on addresses: ${addresses.joinToString { it.hostAddress }}" }
    }

    init {
        // Устанавливаем провайдер BouncyCastle для криптографии.
        Security.addProvider(BouncyCastleProvider())
    }
}
package com.lapcevichme.bookweaverdesktop.server

import com.lapcevichme.bookweaverdesktop.backend.BookManager
import com.lapcevichme.bookweaverdesktop.model.*
import com.lapcevichme.bookweaverdesktop.util.NetworkUtils
import com.lapcevichme.bookweaverdesktop.util.SecurityUtils
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.websocket.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import mu.KotlinLogging
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import java.util.concurrent.atomic.AtomicReference
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

private val logger = KotlinLogging.logger {}

class ServerManager(private val bookManager: BookManager) {
    private val PORT = 8765
    internal val KEYSTORE_FILE = "keystore.bks"
    private val SERVICE_TYPE = "_bookweaver._tcp.local."
    private val SERVICE_NAME = "BookWeaver Desktop Server"
    private val KEY_ALIAS = "bookweaver"

    private val _wsServerState = MutableStateFlow<WsServerState>(WsServerState.Disconnected)
    val serverState = _wsServerState.asStateFlow()

    private var ktorServer: EmbeddedServer<*, *>? = null
    private var jmDnsInstances: List<JmDNS> = emptyList()
    private var fingerprint: String = ""

    internal val peerSession = AtomicReference<WebSocketSession?>()

    // Создаем Json здесь, чтобы он был доступен во всем серверном слое
    internal val json = Json {
        serializersModule = SerializersModule {
            polymorphic(WsMessage::class) {
                subclass(WsRequestBookList::class)
                subclass(WsBookList::class)
                subclass(WsRequestAudio::class)
                subclass(WsAudioStreamEnd::class)
                subclass(WsAudioStreamError::class)
                subclass(WsError::class)
            }
        }
        classDiscriminator = "type"
        ignoreUnknownKeys = true
    }


    fun start() {
        try {
            val (keyStore, calculatedFingerprint, keystorePassword) = SecurityUtils.setupCertificate(KEYSTORE_FILE)
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
                    // ИЗМЕНЕНИЕ: Передаем зависимости в модуль Ktor
                    configureKtorApp(this@ServerManager, bookManager)
                }
            )
            ktorServer!!.start(wait = false)
            keystorePassword.fill('\u0000')

            setupJmDNS()
            _wsServerState.value = WsServerState.ReadyForConnection
            logger.info { "✅ Server started. Certificate fingerprint: $fingerprint" }
        } catch (e: Exception) {
            logger.error(e) { "Error starting server" }
            _wsServerState.value = WsServerState.Error(e.message ?: "Unknown error starting server")
        }
    }

    fun stop() {
        try {
            jmDnsInstances.forEach { it.close() }
            ktorServer?.stop(gracePeriodMillis = 1000L, timeoutMillis = 2000L)
            _wsServerState.value = WsServerState.Disconnected
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
            _wsServerState.value = WsServerState.Error("No active network interfaces found.")
            return
        }
        val connectionInfo = ConnectionInfo(ips = ips, port = PORT, fingerprint = fingerprint)
        val qrCodePayload = json.encodeToString(ConnectionInfo.serializer(), connectionInfo)
        _wsServerState.value = WsServerState.AwaitingConnection(qrCodePayload)
    }

    fun onPeerConnected(session: WebSocketSession, remoteHost: String) {
        _wsServerState.value = WsServerState.PeerConnected(remoteHost)
    }

    fun onPeerDisconnected() {
        _wsServerState.value = WsServerState.ReadyForConnection
    }

    private fun setupJmDNS() {
        val addresses = NetworkUtils.getAllLocalIPs()
        if (addresses.isEmpty()) {
            _wsServerState.value = WsServerState.Error("No network interfaces found.")
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
        Security.addProvider(BouncyCastleProvider())
    }
}


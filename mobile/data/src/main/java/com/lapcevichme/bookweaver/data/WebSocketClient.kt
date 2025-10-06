package com.lapcevichme.bookweaver.data

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.net.SocketException
import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Финальная, "глупая" версия WebSocket клиента.
 * Управляет только самим соединением и передачей сырых данных (текст/байты).
 * Не знает о JSON, аудиофайлах или логике переподключения.
 */
class WebSocketClient @Inject constructor(private val app: Application) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // --- ПУБЛИЧНЫЕ ПОТОКИ ---
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    private val _connectionStatus = MutableStateFlow("Не подключено")
    val connectionStatus = _connectionStatus.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<String>() // Используем SharedFlow для событий
    val incomingMessages = _incomingMessages.asSharedFlow()

    private val _incomingBytes = MutableSharedFlow<ByteString>()
    val incomingBytes = _incomingBytes.asSharedFlow()

    private var webSocket: WebSocket? = null

    // --- ПУБЛИЧНЫЕ МЕТОДЫ ---
    fun connect(ip: String, port: Int, fingerprint: String) {
        if (webSocket != null) {
            addLog("Соединение уже активно.")
            return
        }
        _connectionStatus.value = "Подключение..."
        try {
            val trustManager = createFingerprintTrustManager(fingerprint)
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(trustManager), null)
            }
            val okHttpClient = OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustManager)
                .hostnameVerifier { _, _ -> true }
                .build()
            val serverUrl = "wss://$ip:$port"
            val request = Request.Builder().url(serverUrl).build()
            webSocket = okHttpClient.newWebSocket(request, AppWebSocketListener())
        } catch (e: Exception) {
            addLog("Критическая ошибка SSL: ${e.message}")
            _connectionStatus.value = "Ошибка SSL"
        }
    }

    fun sendMessage(text: String) {
        webSocket?.send(text)
    }

    fun disconnect() {
        // Используем код 1000 для штатного закрытия, чтобы не вызывать логику переподключения
        webSocket?.close(1000, "User disconnected")
        webSocket = null
    }

    // --- СЛУШАТЕЛЬ WEBSOCKET ---
    private inner class AppWebSocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            this@WebSocketClient.webSocket = webSocket
            _connectionStatus.value = "Подключено"
            addLog("✅ WebSocket открыт.")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // Просто передаем сырую строку в поток
            scope.launch { _incomingMessages.emit(text) }
        }



        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // Просто передаем сырые байты в поток
            scope.launch { _incomingBytes.emit(bytes) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            this@WebSocketClient.webSocket = null
            _connectionStatus.value = "Соединение закрыто"
            addLog("🔌 Соединение закрывается: $reason (код $code)")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            this@WebSocketClient.webSocket = null
            _connectionStatus.value = "Ошибка соединения"
            // Не пытаемся переподключиться здесь. Приложение само решит, что делать.
            addLog("☠️ Ошибка соединения: ${t.message}")
        }
    }

    // --- Вспомогательные функции (без изменений) ---
    private fun addLog(message: String) {
        Log.d("WebSocketClient", message)
        scope.launch { _logs.value = (_logs.value + message).takeLast(100) }
    }

    private fun createFingerprintTrustManager(expectedFingerprint: String): X509TrustManager =
        object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (chain.isNullOrEmpty()) throw SocketException("Цепочка сертификатов пуста")
                val serverCert = chain[0]
                val actualFingerprint = calculateFingerprint(serverCert)
                if (!actualFingerprint.equals(expectedFingerprint, ignoreCase = true)) {
                    throw SocketException("ОТПЕЧАТОК НЕ СОВПАДАЕТ!")
                }
                addLog("✅ Отпечаток совпадает.")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

    private fun calculateFingerprint(cert: Certificate): String {
        val keyBytes = (cert as X509Certificate).publicKey.encoded
        val digest = MessageDigest.getInstance("SHA-256").digest(keyBytes)
        return "SHA-256;" + digest.joinToString(":") { "%02x".format(it) }.uppercase()
    }
}


package com.lapcevichme.network

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okio.ByteString
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketException
import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Manages the WebSocket connection, state, and data transfer.
 * It is now a regular class managed by Hilt.
 */
class WebSocketClient @Inject constructor(private val app: Application) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs = app.getSharedPreferences("server_prefs", Context.MODE_PRIVATE)

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    private val _connectionStatus = MutableStateFlow("Не подключено")
    val connectionStatus = _connectionStatus.asStateFlow()

    private val _audioFileUri = MutableStateFlow<Uri?>(null)
    val audioFileUri = _audioFileUri.asStateFlow()

    private var webSocket: WebSocket? = null
    private var isReceivingAudio = false
    private var audioFileStream: FileOutputStream? = null
    private var currentAudioFile: File? = null

    private val json =
        Json { ignoreUnknownKeys = true; isLenient = true; classDiscriminator = "type" }
    private var retryAttempts = 0
    private val maxRetries = 3

    fun connect(ip: String, port: Int, fingerprint: String) {
        if (webSocket != null) {
            addLog("Соединение уже активно.")
            return
        }
        prefs.edit().putString("last_server_ip", ip).putInt("last_server_port", port).apply()
        addLog("Сохранён lastIP: $ip:$port для retry.")

        _connectionStatus.value = "Подключение..."
        addLog("Создание безопасного клиента...")
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
            addLog("Подключение к $serverUrl...")
            webSocket = okHttpClient.newWebSocket(request, AppWebSocketListener())
        } catch (e: Exception) {
            addLog("Критическая ошибка SSL: ${e.message}")
            _connectionStatus.value = "Ошибка SSL"
        }
    }

    fun disconnect() {
        addLog("Отключение...")
        retryAttempts = 0
        webSocket?.close(1000, "User disconnected")
        webSocket = null
    }

    fun requestAudioFile(fileName: String) {
        if (webSocket == null) {
            addLog("WebSocket не подключен.")
            return
        }
        val requestMsg = WsRequestAudio(filePath = fileName)
        val jsonStr = json.encodeToString(WsMessage.serializer(), requestMsg)
        webSocket?.send(jsonStr)
        addLog("📤 Запрос аудиофайла: $fileName")
        isReceivingAudio = true
        currentAudioFile = File(app.cacheDir, "temp_audio_${System.currentTimeMillis()}.mp3")
        audioFileStream = currentAudioFile!!.outputStream()
        _connectionStatus.value = "Получение аудио..."
    }

    private fun finishAudioReception() {
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    audioFileStream?.close()
                } catch (e: IOException) {
                    addLog("Ошибка при закрытии файла: ${e.message}")
                }
            }
            currentAudioFile?.let { file ->
                if (file.exists() && file.length() > 0) {
                    addLog("✅ Аудиофайл успешно получен (${file.length()} байт).")
                    val uri = FileProvider.getUriForFile(app, "${app.packageName}.provider", file)
                    _audioFileUri.value = uri
                } else {
                    addLog("‼️ Получен пустой аудиофайл.")
                }
            }
            currentAudioFile = null
            isReceivingAudio = false
            // Only change status if it was "Receiving"
            if (_connectionStatus.value == "Получение аудио...") {
                _connectionStatus.value = "Подключено"
            }
        }
    }

    fun clearAudioFileUri() {
        _audioFileUri.value = null
    }

    private fun startRetryDiscovery() {
        if (retryAttempts >= maxRetries) {
            addLog("❌ Достигнут лимит попыток переподключения.")
            return
        }
        retryAttempts++
        val delayMs = (5000L * retryAttempts)
        scope.launch {
            addLog("🔄 Пауза ${delayMs / 1000}s перед попыткой #$retryAttempts...")
            delay(delayMs)
            val fingerprint = prefs.getString("server_fingerprint", null)
            val lastIp = prefs.getString("last_server_ip", null)
            val lastPort = prefs.getInt("last_server_port", 8765)
            if (fingerprint != null && lastIp != null) {
                addLog("🔄 Попытка переподключения #$retryAttempts к $lastIp:$lastPort...")
                connect(lastIp, lastPort, fingerprint)
            } else {
                addLog("⚠️ Нет данных для переподключения.")
            }
        }
    }

    private fun createFingerprintTrustManager(expectedFingerprint: String): X509TrustManager =
        object : X509TrustManager {
            override fun checkClientTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?
            ) {
            }

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

    private fun addLog(message: String) {
        Log.d("WebSocketClient", message)
        scope.launch {
            _logs.value = (_logs.value + message).takeLast(100) // Keep last 100 logs
        }
    }

    private inner class AppWebSocketListener : okhttp3.WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            this@WebSocketClient.webSocket = webSocket
            _connectionStatus.value = "Подключено"
            addLog("✅ WebSocket открыт.")
            retryAttempts = 0
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val message = json.decodeFromString(WsMessage.serializer(), text)
                when (message) {
                    is WsAudioStreamEnd -> finishAudioReception()
                    is WsAudioStreamError -> {
                        addLog("❌ Ошибка стрима: ${message.error}")
                        finishAudioReception()
                    }

                    is WsError -> {
                        addLog("‼️ Ошибка от сервера: ${message.message}")
                        if (isReceivingAudio) finishAudioReception()
                    }

                    else -> addLog("❓ Неизвестное сообщение: $text")
                }
            } catch (e: Exception) {
                addLog("Ошибка парсинга: ${e.message}")
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (isReceivingAudio && audioFileStream != null) {
                try {
                    audioFileStream?.write(bytes.toByteArray())
                } catch (e: IOException) {
                    addLog("❌ Ошибка записи файла: ${e.message}")
                    finishAudioReception()
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (isReceivingAudio) finishAudioReception()
            this@WebSocketClient.webSocket = null
            _connectionStatus.value = "Соединение закрыто"
            addLog("🔌 Соединение закрывается: $reason (код $code)")
            if (code != 1000) {
                startRetryDiscovery()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (isReceivingAudio) finishAudioReception()
            this@WebSocketClient.webSocket = null
            _connectionStatus.value = "Ошибка соединения"
            addLog("☠️ Ошибка соединения: ${t.message}")
            startRetryDiscovery()
        }
    }
}

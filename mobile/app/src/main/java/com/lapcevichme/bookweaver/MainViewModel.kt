package com.lapcevichme.bookweaver

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import com.lapcevichme.network.ConnectionInfo
import com.lapcevichme.network.ServerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    private val application: Application // Инжектируем Application для доступа к SharedPreferences
) : ViewModel() {

    val connectionStatus: StateFlow<String> = serverRepository.connectionStatus
    val audioFileUri: StateFlow<Uri?> = serverRepository.receivedAudio
    val logs: StateFlow<List<String>> = serverRepository.logs

    private val prefs = application.getSharedPreferences("server_prefs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val FINGERPRINT_KEY = "server_fingerprint"
    }

    /**
     * Запускает автоподключение при старте, если есть сохраненный отпечаток.
     */
    fun reconnectIfPossible() {
        val fingerprint = prefs.getString(FINGERPRINT_KEY, null)
        if (fingerprint != null) {
            // Если отпечаток найден, запускаем поиск и подключение
            serverRepository.findAndConnectToServer(fingerprint)
        }
    }

    fun handleQrCodeResult(contents: String?) {
        if (contents.isNullOrBlank()) return
        try {
            val info = json.decodeFromString(ConnectionInfo.serializer(), contents)
            // Сохраняем отпечаток на будущее
            prefs.edit { putString(FINGERPRINT_KEY, info.fingerprint) }
            // Запускаем поиск и подключение
            serverRepository.findAndConnectToServer(info.fingerprint)
        } catch (e: Exception) {
            // Обработка ошибки парсинга JSON
            println("Error parsing QR code: ${e.message}")
        }
    }

    fun requestAudioFile(fileName: String) {
        serverRepository.requestAudioFile(fileName)
    }

    fun clearAudioFileUri() {
        serverRepository.clearAudioFileUri()
    }

    override fun onCleared() {
        super.onCleared()
    }
}

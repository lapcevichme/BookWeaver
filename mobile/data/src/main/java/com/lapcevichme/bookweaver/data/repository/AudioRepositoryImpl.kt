package com.lapcevichme.bookweaver.data.repository

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import com.lapcevichme.bookweaver.data.WsAudioStreamEnd
import com.lapcevichme.bookweaver.data.WsMessage
import com.lapcevichme.bookweaver.data.WsRequestAudio
import com.lapcevichme.bookweaver.domain.repository.AudioRepository
import com.lapcevichme.bookweaver.domain.repository.ServerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okio.ByteString
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class AudioRepositoryImpl @Inject constructor(
    private val serverRepository: ServerRepository,
    private val json: Json,
    private val application: Application
) : AudioRepository {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _receivedAudioUri = MutableStateFlow<Uri?>(null)
    override val receivedAudioSource: StateFlow<String?> = _receivedAudioUri.map { uri ->
        uri?.toString()
    }.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null // Начальное значение теперь null
    )

    private var isReceivingAudio = false
    private var audioFileStream: FileOutputStream? = null
    private var tempAudioFile: File? = null

    init {
        // Слушаем входящие байты
        serverRepository.incomingBytes
            .onEach { bytes -> handleIncomingBytes(bytes) }
            .launchIn(scope)

        // Слушаем текстовые сообщения, чтобы поймать конец передачи
        serverRepository.incomingMessages
            .onEach { messageString -> handleIncomingMessage(messageString) }
            .launchIn(scope)
    }

    override fun requestAudioFile(filePath: String) {
        if (isReceivingAudio) return

        scope.launch {
            // 1. Готовимся к приему файла
            isReceivingAudio = true
            tempAudioFile = File.createTempFile("audio_chapter", ".mp3", application.cacheDir)
            audioFileStream = FileOutputStream(tempAudioFile)

            // 2. Отправляем запрос на сервер
            val request = WsRequestAudio(filePath = filePath)
            val requestString = json.encodeToString(WsMessage.serializer(), request)
            serverRepository.sendMessage(requestString)
        }
    }

    private fun handleIncomingBytes(bytes: ByteString) {
        if (!isReceivingAudio) return

        try {
            audioFileStream?.write(bytes.toByteArray())
        } catch (e: IOException) {
            finishAudioReception(isSuccess = false)
        }
    }

    private fun handleIncomingMessage(messageString: String) {
        try {
            when (json.decodeFromString(WsMessage.serializer(), messageString)) {
                is WsAudioStreamEnd -> {
                    finishAudioReception(isSuccess = true)
                }

                else -> { /* Игнорируем */
                }
            }
        } catch (e: Exception) {
            // Log error
        }
    }

    private fun finishAudioReception(isSuccess: Boolean) {
        try {
            audioFileStream?.close()
        } catch (e: IOException) {
            // Log error
        } finally {
            if (isSuccess && tempAudioFile != null) {
                // Если все успешно, создаем Uri и отдаем его наружу
                val uri = FileProvider.getUriForFile(
                    application,
                    "${application.packageName}.provider",
                    tempAudioFile!!
                )
                _receivedAudioUri.value = uri
            } else {
                // Если была ошибка, удаляем временный файл
                tempAudioFile?.delete()
            }
            // Сбрасываем состояние
            isReceivingAudio = false
            audioFileStream = null
            tempAudioFile = null
        }
    }

    override fun clearAudioFileUri() {
        _receivedAudioUri.value = null
    }
}

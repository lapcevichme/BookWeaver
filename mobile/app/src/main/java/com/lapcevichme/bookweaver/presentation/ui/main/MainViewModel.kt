package com.lapcevichme.bookweaver.presentation.ui.main

import androidx.lifecycle.ViewModel
// --- ДОБАВЛЯЕМ ИМПОРТЫ ---
import com.lapcevichme.bookweaver.data.ConnectionInfo // Импортируем DTO из :data
import com.lapcevichme.bookweaver.domain.usecase.connection.GetConnectionStatusUseCase
import com.lapcevichme.bookweaver.domain.usecase.connection.GetLogsUseCase
import com.lapcevichme.bookweaver.domain.usecase.connection.HandleQrCodeUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json // Импортируем Json
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    getConnectionStatusUseCase: GetConnectionStatusUseCase,
    getLogsUseCase: GetLogsUseCase,
    private val handleQrCodeUseCase: HandleQrCodeUseCase
) : ViewModel() {

    val connectionStatus: StateFlow<String> = getConnectionStatusUseCase()
     val logs: StateFlow<List<String>> = getLogsUseCase()

    // --- ДОБАВЛЯЕМ ПАРСЕР ---
    private val json = Json { ignoreUnknownKeys = true }

    // --- ОБНОВЛЯЕМ МЕТОД ---
    fun handleQrCodeResult(contents: String?) {
        if (contents.isNullOrBlank()) return

        try {
            // 1. Парсим строку в DTO прямо здесь
            val info = json.decodeFromString(ConnectionInfo.serializer(), contents)
            // 2. Вызываем use-case с уже чистыми данными
            handleQrCodeUseCase(info.fingerprint)
        } catch (e: Exception) {
            // Здесь можно показать ошибку пользователю
            println("Ошибка парсинга QR-кода: ${e.message}")
        }
    }
}
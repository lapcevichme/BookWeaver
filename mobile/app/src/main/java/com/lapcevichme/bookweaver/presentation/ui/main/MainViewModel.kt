package com.lapcevichme.bookweaver.presentation.ui.main

import androidx.lifecycle.ViewModel
import com.lapcevichme.bookweaver.domain.usecase.connection.GetConnectionStatusUseCase
import com.lapcevichme.bookweaver.domain.usecase.connection.HandleQrCodeUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    getConnectionStatusUseCase: GetConnectionStatusUseCase,
    private val handleQrCodeUseCase: HandleQrCodeUseCase
) : ViewModel() {

    // Просто получаем готовые потоки данных от use-кейсов
    val connectionStatus: StateFlow<String> = getConnectionStatusUseCase()
    // val logs: StateFlow<List<String>> = getLogsUseCase()

    // ViewModel теперь не знает ни о каких SharedPreferences или JSON
    fun handleQrCodeResult(contents: String?) {
        handleQrCodeUseCase(contents)
    }
}
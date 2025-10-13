package com.lapcevichme.bookweaverdesktop.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.bookweaverdesktop.config.ConfigManager
import com.lapcevichme.bookweaverdesktop.settings.AppSettings
import com.lapcevichme.bookweaverdesktop.settings.SettingsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SettingsUiState {
    data object Loading : SettingsUiState
    data class Success(val settings: AppSettings, val configContent: String) : SettingsUiState
    data class Error(val message: String) : SettingsUiState
}

class SettingsAndAssetsViewModel(
    private val settingsManager: SettingsManager,
    private val configManager: ConfigManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<SettingsUiState>(SettingsUiState.Loading)
    val uiState = _uiState.asStateFlow()

    init {
        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            _uiState.value = SettingsUiState.Loading
            try {
                val loadedSettings = settingsManager.loadSettings()
                val config = configManager.loadConfigContent()
                _uiState.value = SettingsUiState.Success(loadedSettings, config)
            } catch (e: Exception) {
                _uiState.value = SettingsUiState.Error("Не удалось загрузить данные: ${e.message}")
            }
        }
    }

    fun saveSettings(newSettings: AppSettings) {
        viewModelScope.launch {
            try {
                settingsManager.saveSettings(newSettings)
                // Перезагружаем все данные, чтобы UI был консистентным
                loadData()
            } catch (e: Exception) {
                // В реальном приложении здесь стоит показать ошибку пользователю
                println("Ошибка сохранения настроек: ${e.message}")
            }
        }
    }

    // Сохранение содержимого config.py
    fun saveConfig(content: String) {
        viewModelScope.launch {
            val success = configManager.saveConfigContent(content)
            if (success) {
                // Перезагружаем данные, чтобы в editorText попало актуальное содержимое
                loadData()
            } else {
                // Можно обновить стейт с сообщением об ошибке
                println("Не удалось сохранить config.py")
            }
        }
    }
}

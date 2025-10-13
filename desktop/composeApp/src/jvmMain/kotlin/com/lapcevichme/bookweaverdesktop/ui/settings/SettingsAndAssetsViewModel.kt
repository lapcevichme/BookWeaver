package com.lapcevichme.bookweaverdesktop.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.bookweaverdesktop.config.ConfigManager
import com.lapcevichme.bookweaverdesktop.settings.AppSettings
import com.lapcevichme.bookweaverdesktop.settings.SettingsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface SettingsUiState {
    data object Loading : SettingsUiState
    data class Success(val settings: AppSettings, val configContent: String, val message: String? = null) : SettingsUiState
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
            val settingsResult = settingsManager.loadSettings()
            val configResult = configManager.loadConfigContent()

            settingsResult.onSuccess { settings ->
                configResult.onSuccess { configContent ->
                    _uiState.value = SettingsUiState.Success(settings, configContent)
                }.onFailure { configError ->
                    _uiState.value = SettingsUiState.Error("Ошибка загрузки config.py: ${configError.message}")
                }
            }.onFailure { settingsError ->
                _uiState.value = SettingsUiState.Error("Ошибка загрузки настроек: ${settingsError.message}")
            }
        }
    }

    fun saveSettings(newSettings: AppSettings) {
        viewModelScope.launch {
            settingsManager.saveSettings(newSettings)
                .onSuccess {
                    // Перезагружаем все данные, чтобы UI был консистентным
                    loadData()
                    // TODO: Показать Snackbar об успехе
                }
                .onFailure { error ->
                    _uiState.update {
                        if (it is SettingsUiState.Success) it.copy(message = "Ошибка сохранения настроек: ${error.message}")
                        else SettingsUiState.Error("Ошибка сохранения настроек: ${error.message}")
                    }
                }
        }
    }

    fun saveConfig(content: String) {
        viewModelScope.launch {
            configManager.saveConfigContent(content)
                .onSuccess {
                    // Перезагружаем данные, чтобы в editorText попало актуальное содержимое
                    loadData()
                    // TODO: Показать Snackbar об успехе
                }
                .onFailure { error ->
                    _uiState.update {
                        if (it is SettingsUiState.Success) it.copy(message = "Ошибка сохранения config.py: ${error.message}")
                        else SettingsUiState.Error("Ошибка сохранения config.py: ${error.message}")
                    }
                }
        }
    }
}

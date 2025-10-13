package com.lapcevichme.bookweaverdesktop.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.bookweaverdesktop.data.config.ConfigManager
import com.lapcevichme.bookweaverdesktop.core.settings.AppSettings
import com.lapcevichme.bookweaverdesktop.core.settings.SettingsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ИЗМЕНЕНИЕ: Новая структура состояний, чтобы разделить ошибки
sealed interface SettingsUiState {
    data object Loading : SettingsUiState
    data class Loaded(
        val settings: AppSettings,
        val configContent: String?, // Может быть null, если config.py не загрузился
        val configError: String?,   // Сообщение об ошибке для config.py
        val message: String? = null  // Для Snackbar сообщений
    ) : SettingsUiState
    data class Error(val message: String) : SettingsUiState // Критическая ошибка (не удалось загрузить settings.json)
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

            // Сначала пытаемся загрузить базовые настройки. Это критично.
            settingsManager.loadSettings().onSuccess { settings ->
                // Настройки загружены. Теперь пытаемся загрузить config.py.
                // Ошибка здесь не является критической для вкладки "Настройки".
                val configResult = configManager.loadConfigContent()

                _uiState.value = SettingsUiState.Loaded(
                    settings = settings,
                    configContent = configResult.getOrNull(),
                    configError = configResult.exceptionOrNull()?.message
                )

            }.onFailure { settingsError ->
                // Если не удалось загрузить даже settings.json, это критическая ошибка.
                _uiState.value = SettingsUiState.Error("Критическая ошибка загрузки настроек: ${settingsError.message}")
            }
        }
    }

    fun saveSettings(newSettings: AppSettings) {
        viewModelScope.launch {
            settingsManager.saveSettings(newSettings)
                .onSuccess {
                    loadData()
                }
                .onFailure { error ->
                    _uiState.update {
                        if (it is SettingsUiState.Loaded) it.copy(message = "Ошибка сохранения настроек: ${error.message}")
                        else SettingsUiState.Error("Ошибка сохранения настроек: ${error.message}")
                    }
                }
        }
    }

    fun saveConfig(content: String) {
        viewModelScope.launch {
            configManager.saveConfigContent(content)
                .onSuccess {
                    loadData()
                }
                .onFailure { error ->
                    _uiState.update {
                        if (it is SettingsUiState.Loaded) it.copy(message = "Ошибка сохранения config.py: ${error.message}")
                        else SettingsUiState.Error("Ошибка сохранения config.py: ${error.message}")
                    }
                }
        }
    }
}


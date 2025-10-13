package com.lapcevichme.bookweaverdesktop.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.bookweaverdesktop.core.settings.AppSettings
import com.lapcevichme.bookweaverdesktop.core.settings.SettingsManager
import com.lapcevichme.bookweaverdesktop.domain.usecase.GetConfigContentUseCase
import com.lapcevichme.bookweaverdesktop.domain.usecase.SaveConfigContentUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface SettingsUiState {
    data object Loading : SettingsUiState
    data class Loaded(
        val settings: AppSettings,
        val configContent: String?,
        val configError: String?,
        val message: String? = null
    ) : SettingsUiState
    data class Error(val message: String) : SettingsUiState
}

class SettingsAndAssetsViewModel(
    private val settingsManager: SettingsManager, // Оставляем, т.к. AppSettings - это UI-модель
    private val getConfigContentUseCase: GetConfigContentUseCase,
    private val saveConfigContentUseCase: SaveConfigContentUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow<SettingsUiState>(SettingsUiState.Loading)
    val uiState = _uiState.asStateFlow()

    init {
        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            _uiState.value = SettingsUiState.Loading

            settingsManager.loadSettings().onSuccess { settings ->
                val configResult = getConfigContentUseCase()

                _uiState.value = SettingsUiState.Loaded(
                    settings = settings,
                    configContent = configResult.getOrNull(),
                    configError = configResult.exceptionOrNull()?.message
                )

            }.onFailure { settingsError ->
                _uiState.value = SettingsUiState.Error("Критическая ошибка загрузки настроек: ${settingsError.message}")
            }
        }
    }

    fun saveSettings(newSettings: AppSettings) {
        viewModelScope.launch {
            settingsManager.saveSettings(newSettings)
                .onSuccess {
                    // Перезагружаем все данные, чтобы UI обновился
                    loadData()
                }
                .onFailure { error ->
                    updateStateWithMessage("Ошибка сохранения настроек: ${error.message}", error)
                }
        }
    }

    fun saveConfig(content: String) {
        viewModelScope.launch {
            saveConfigContentUseCase(content)
                .onSuccess {
                    // Перезагружаем все данные, чтобы UI обновился
                    loadData()
                }
                .onFailure { error ->
                    updateStateWithMessage("Ошибка сохранения config.py: ${error.message}", error)
                }
        }
    }

    private fun updateStateWithMessage(message: String, error: Throwable) {
        _uiState.update { currentState ->
            if (currentState is SettingsUiState.Loaded) {
                currentState.copy(message = message)
            } else {
                SettingsUiState.Error("Ошибка: ${error.message}")
            }
        }
    }
}

package com.lapcevichme.bookweaver.features.settings.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.bookweaver.domain.model.ThemeSetting
import com.lapcevichme.bookweaver.domain.usecase.settings.GetThemeSettingUseCase
import com.lapcevichme.bookweaver.domain.usecase.settings.SaveThemeSettingUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppSettingsViewModel @Inject constructor(
    private val getThemeSettingUseCase: GetThemeSettingUseCase,
    private val saveThemeSettingUseCase: SaveThemeSettingUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppSettingsUiState())
    val uiState = _uiState.asStateFlow()

    init {
        // Сразу подписываемся на изменения темы
        getThemeSettingUseCase()
            .onEach { themeSetting ->
                _uiState.update { it.copy(isLoading = false, selectedTheme = themeSetting) }
            }
            .launchIn(viewModelScope)
    }

    fun onEvent(event: AppSettingsEvent) {
        when (event) {
            is AppSettingsEvent.OnThemeSelected -> {
                saveTheme(event.theme)
            }
        }
    }

    private fun saveTheme(theme: ThemeSetting) {
        viewModelScope.launch {
            saveThemeSettingUseCase(theme)
            // Состояние UI обновится автоматически благодаря Flow в init {}
        }
    }
}
package com.lapcevichme.bookweaver.features.settings.app

import com.lapcevichme.bookweaver.domain.model.ThemeSetting

/**
 * Состояние UI для экрана настроек приложения.
 */
data class AppSettingsUiState(
    val isLoading: Boolean = true,
    val selectedTheme: ThemeSetting = ThemeSetting.SYSTEM
)

/**
 * События, которые UI может отправить в ViewModel.
 */
sealed interface AppSettingsEvent {
    data class OnThemeSelected(val theme: ThemeSetting) : AppSettingsEvent
}
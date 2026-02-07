package com.lapcevichme.bookweaver.features.settings.app

import com.lapcevichme.bookweaver.domain.model.ServerConnection
import com.lapcevichme.bookweaver.domain.model.ThemeSetting

data class AppSettingsUiState(
    val isLoading: Boolean = true,
    val selectedTheme: ThemeSetting = ThemeSetting.SYSTEM,
    val serverConnection: ServerConnection? = null
)

sealed interface AppSettingsEvent {
    data class OnThemeSelected(val theme: ThemeSetting) : AppSettingsEvent
}
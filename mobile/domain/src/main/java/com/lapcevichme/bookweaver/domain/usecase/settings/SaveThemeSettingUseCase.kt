package com.lapcevichme.bookweaver.domain.usecase.settings

import com.lapcevichme.bookweaver.domain.model.ThemeSetting
import com.lapcevichme.bookweaver.domain.repository.UserPreferencesRepository
import javax.inject.Inject

class SaveThemeSettingUseCase @Inject constructor(
    private val repository: UserPreferencesRepository
) {
    suspend operator fun invoke(theme: ThemeSetting) {
        repository.saveThemeSetting(theme.storageKey)
    }
}
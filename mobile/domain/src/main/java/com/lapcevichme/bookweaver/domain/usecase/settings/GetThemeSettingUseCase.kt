package com.lapcevichme.bookweaver.domain.usecase.settings

import com.lapcevichme.bookweaver.domain.model.ThemeSetting
import com.lapcevichme.bookweaver.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class GetThemeSettingUseCase @Inject constructor(
    private val repository: UserPreferencesRepository
) {
    operator fun invoke(): Flow<ThemeSetting> {
        return repository.getThemeSetting().map { key ->
            ThemeSetting.entries.firstOrNull { it.storageKey == key } ?: ThemeSetting.SYSTEM
        }
    }
}
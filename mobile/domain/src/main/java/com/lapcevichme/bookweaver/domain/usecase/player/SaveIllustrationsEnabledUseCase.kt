package com.lapcevichme.bookweaver.domain.usecase.player

import com.lapcevichme.bookweaver.domain.repository.UserPreferencesRepository
import javax.inject.Inject

class SaveIllustrationsEnabledUseCase @Inject constructor(
    private val repository: UserPreferencesRepository
) {
    suspend operator fun invoke(enabled: Boolean) = repository.saveIllustrationsEnabled(enabled)
}

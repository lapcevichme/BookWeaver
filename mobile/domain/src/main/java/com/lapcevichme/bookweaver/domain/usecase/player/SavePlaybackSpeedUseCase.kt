package com.lapcevichme.bookweaver.domain.usecase.player

import com.lapcevichme.bookweaver.domain.repository.UserPreferencesRepository
import javax.inject.Inject

class SavePlaybackSpeedUseCase @Inject constructor(
    private val repository: UserPreferencesRepository
) {
    suspend operator fun invoke(speed: Float) = repository.savePlaybackSpeed(speed)
}

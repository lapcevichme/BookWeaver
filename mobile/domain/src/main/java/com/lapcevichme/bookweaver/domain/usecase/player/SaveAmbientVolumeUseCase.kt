package com.lapcevichme.bookweaver.domain.usecase.player

import com.lapcevichme.bookweaver.domain.repository.UserPreferencesRepository
import javax.inject.Inject

class SaveAmbientVolumeUseCase @Inject constructor(
    private val repository: UserPreferencesRepository
) {
    suspend operator fun invoke(volume: Float) = repository.saveAmbientVolume(volume)
}

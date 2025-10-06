package com.lapcevichme.bookweaver.domain.usecase.audio

import com.lapcevichme.bookweaver.domain.repository.AudioRepository
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

class GetAudioSourceUseCase @Inject constructor(private val audioRepository: AudioRepository) {
    operator fun invoke(): StateFlow<String?> {
        return audioRepository.receivedAudioSource
    }
}

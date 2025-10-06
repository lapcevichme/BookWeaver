package com.lapcevichme.bookweaver.domain.usecase.audio

import com.lapcevichme.bookweaver.domain.repository.AudioRepository
import javax.inject.Inject

class RequestAudioUseCase @Inject constructor(private val audioRepository: AudioRepository) {
    operator fun invoke(filePath: String) {
        audioRepository.requestAudioFile(filePath)
    }
}

package com.lapcevichme.bookweaver.domain.usecase.audio

import android.net.Uri
import com.lapcevichme.bookweaver.domain.repository.AudioRepository
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

class GetAudioUriUseCase @Inject constructor(private val audioRepository: AudioRepository) {
    operator fun invoke(): StateFlow<Uri?> {
        return audioRepository.receivedAudioUri
    }
}

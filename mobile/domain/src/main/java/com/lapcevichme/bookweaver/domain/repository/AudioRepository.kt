package com.lapcevichme.bookweaver.domain.repository

import kotlinx.coroutines.flow.StateFlow

interface AudioRepository {
    val receivedAudioSource: StateFlow<String?>
    fun requestAudioFile(filePath: String)
    fun clearAudioFileUri()
}
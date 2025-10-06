package com.lapcevichme.bookweaver.domain.repository

import android.net.Uri
import kotlinx.coroutines.flow.StateFlow

interface AudioRepository {
    val receivedAudioUri: StateFlow<Uri?>
    fun requestAudioFile(filePath: String)
    fun clearAudioFileUri()
}
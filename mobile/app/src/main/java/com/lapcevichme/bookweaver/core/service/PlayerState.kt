package com.lapcevichme.bookweaver.core.service

import android.graphics.Bitmap

data class PlayerState(
    val isPlaying: Boolean = false,
    val currentPosition: Long = 0L,
    val duration: Long = 0L,
    val fileName: String = "",
    val albumArt: Bitmap? = null,
    val playbackSpeed: Float = 1.0f,
    val currentSubtitle: CharSequence = "",
    val subtitlesEnabled: Boolean = true,
    val error: String? = null,
    val loadedChapterId: String = ""
)
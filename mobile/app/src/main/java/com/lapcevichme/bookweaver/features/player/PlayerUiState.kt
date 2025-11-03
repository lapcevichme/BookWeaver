package com.lapcevichme.bookweaver.features.player

import com.lapcevichme.bookweaver.domain.model.PlayerChapterInfo

data class LoadCommand(
    val playWhenReady: Boolean,
    val seekToPositionMs: Long? = null
)


data class PlayerUiState(
    val isLoading: Boolean = false,
    val bookId: String? = null,
    val chapterId: String? = null,
    val chapterInfo: PlayerChapterInfo? = null,
    val error: String? = null,
    val loadCommand: LoadCommand? = null,
    val clearService: Boolean = false,
    val playbackSpeed: Float = 1.0f,
    val ambientVolume: Float = 0.5f
)



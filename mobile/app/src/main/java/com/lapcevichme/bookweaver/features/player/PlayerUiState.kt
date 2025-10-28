package com.lapcevichme.bookweaver.features.player

import com.lapcevichme.bookweaver.domain.model.PlayerChapterInfo

data class LoadCommand(
    val playWhenReady: Boolean,
    val seekToPositionMs: Long? = null
)

data class PlayerUiState(
    val isLoading: Boolean = false,
    val chapterInfo: PlayerChapterInfo? = null,
    val error: String? = null,
    val loadCommand: LoadCommand? = null
)


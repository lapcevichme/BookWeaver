package com.lapcevichme.bookweaver.features.player

import com.lapcevichme.bookweaver.domain.model.PlayerChapterInfo

data class PlayerUiState(
    val isLoading: Boolean = false,
    val chapterInfo: PlayerChapterInfo? = null,
    val error: String? = null
)
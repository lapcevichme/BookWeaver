package com.lapcevichme.bookweaver.domain.usecase.player

import com.lapcevichme.bookweaver.domain.model.PlaybackEntry
import com.lapcevichme.bookweaver.domain.repository.BookRepository
import javax.inject.Inject

class GetChapterPlaybackDataUseCase @Inject constructor(
    private val bookRepository: BookRepository
) {
    suspend operator fun invoke(
        bookId: String,
        chapterId: String
    ): Result<Pair<List<PlaybackEntry>, String>> {
        return bookRepository.getPlaybackData(bookId, chapterId)
    }
}


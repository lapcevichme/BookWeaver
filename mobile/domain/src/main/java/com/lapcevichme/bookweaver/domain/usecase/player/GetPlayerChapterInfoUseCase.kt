package com.lapcevichme.bookweaver.domain.usecase.player

import com.lapcevichme.bookweaver.domain.model.PlayerChapterInfo
import com.lapcevichme.bookweaver.domain.repository.BookRepository
import javax.inject.Inject

class GetPlayerChapterInfoUseCase @Inject constructor(
    private val bookRepository: BookRepository
) {
    suspend operator fun invoke(bookId: String, chapterId: String): Result<PlayerChapterInfo> {
        return bookRepository.getPlayerChapterInfo(bookId, chapterId)
    }
}

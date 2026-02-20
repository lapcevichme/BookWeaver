package com.lapcevichme.bookweaver.domain.usecase.player

import com.lapcevichme.bookweaver.domain.repository.BookRepository
import javax.inject.Inject

class SaveListenProgressUseCase @Inject constructor(
    private val bookRepository: BookRepository
) {
    suspend operator fun invoke(bookId: String, chapterId: String, position: Long) {
        bookRepository.saveListenProgress(bookId, chapterId, position)
    }
}

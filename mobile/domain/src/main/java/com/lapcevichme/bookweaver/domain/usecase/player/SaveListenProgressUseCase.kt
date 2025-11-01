package com.lapcevichme.bookweaver.domain.usecase.player

import com.lapcevichme.bookweaver.domain.repository.BookRepository
import javax.inject.Inject

/**
 * UseCase для сохранения прогресса прослушивания в базу данных.
 */
class SaveListenProgressUseCase @Inject constructor(
    private val bookRepository: BookRepository
) {
    /**
     * Сохраняет позицию [position] для главы [chapterId] в книге [bookId].
     */
    suspend operator fun invoke(bookId: String, chapterId: String, position: Long) {
        bookRepository.saveListenProgress(bookId, chapterId, position)
    }
}

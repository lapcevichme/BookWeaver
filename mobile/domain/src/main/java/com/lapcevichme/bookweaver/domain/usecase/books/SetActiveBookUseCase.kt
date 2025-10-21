package com.lapcevichme.bookweaver.domain.usecase.books

import com.lapcevichme.bookweaver.domain.repository.BookRepository
import javax.inject.Inject

/**
 * UseCase for setting the currently active book.
 * This is the missing piece that updates the DataStore.
 */
class SetActiveBookUseCase @Inject constructor(
    private val bookRepository: BookRepository
) {
    suspend operator fun invoke(bookId: String) {
        bookRepository.setActiveBookId(bookId)
    }
}

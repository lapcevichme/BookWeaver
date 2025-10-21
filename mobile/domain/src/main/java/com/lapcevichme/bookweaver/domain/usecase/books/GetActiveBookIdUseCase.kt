package com.lapcevichme.bookweaver.domain.usecase.books

import com.lapcevichme.bookweaver.domain.repository.BookRepository
import javax.inject.Inject

/**
 * UseCase for getting the currently active book's ID once.
 * This is useful for initial checks, for example, on app startup.
 */
class GetActiveBookIdUseCase @Inject constructor(
    private val bookRepository: BookRepository
) {
    suspend operator fun invoke(): String? {
        return bookRepository.getActiveBookId()
    }
}

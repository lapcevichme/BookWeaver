package com.lapcevichme.bookweaver.domain.usecase.books

import com.lapcevichme.bookweaver.domain.repository.BookRepository
import javax.inject.Inject

/**
 * Use case для удаления локально сохраненной книги.
 */
class DeleteBookUseCase @Inject constructor(
    private val bookRepository: BookRepository
) {
    /**
     * @param bookId Уникальный идентификатор книги (имя папки).
     * @return Result, содержащий либо Unit при успехе, либо ошибку.
     */
    suspend operator fun invoke(bookId: String): Result<Unit> {
        return bookRepository.deleteBook(bookId)
    }
}

package com.lapcevichme.bookweaver.domain.usecase.books

import com.lapcevichme.bookweaver.domain.model.BookDetails
import com.lapcevichme.bookweaver.domain.repository.BookRepository
import javax.inject.Inject


/**
 * Use case для получения детальной информации о книге.
 */
class GetBookDetailsUseCase @Inject constructor(private val bookRepository: BookRepository) {
    suspend operator fun invoke(bookId: String): Result<BookDetails> {
        return bookRepository.getBookDetails(bookId)
    }
}

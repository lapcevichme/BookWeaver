package com.lapcevichme.bookweaver.domain.usecase.books

import com.lapcevichme.bookweaver.domain.model.Book
import com.lapcevichme.bookweaver.domain.repository.BookRepository
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

class GetBooksUseCase @Inject constructor(private val bookRepository: BookRepository) {
    operator fun invoke(): StateFlow<List<Book>> {
        return bookRepository.books
    }
}

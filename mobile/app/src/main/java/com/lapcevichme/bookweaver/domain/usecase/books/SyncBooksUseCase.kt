package com.lapcevichme.bookweaver.domain.usecase.books

import com.lapcevichme.bookweaver.domain.repository.BookRepository
import javax.inject.Inject

class SyncBooksUseCase @Inject constructor(private val bookRepository: BookRepository) {
    operator fun invoke() {
        bookRepository.requestBookList()
    }
}

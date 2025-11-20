package com.lapcevichme.bookweaver.domain.usecase.books

import com.lapcevichme.bookweaver.domain.model.Book
import com.lapcevichme.bookweaver.domain.repository.BookRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Use-case для получения списка всех книг, доступных пользователю.
 * Этот use-case запрашивает у [BookRepository] единый список,
 * который может включать как локальные, так и удаленные (серверные) книги.
 */
class GetBooksUseCase @Inject constructor(
    private val bookRepository: BookRepository
) {
    operator fun invoke(): Flow<List<Book>> {
        return bookRepository.getBooks()
    }
}

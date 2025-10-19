package com.lapcevichme.bookweaver.domain.usecase.books

import com.lapcevichme.bookweaver.domain.model.BookCharacter
import com.lapcevichme.bookweaver.domain.repository.BookRepository
import javax.inject.Inject

/**
 * Use case для получения списка всех персонажей для указанной книги.
 */
class GetCharactersUseCase @Inject constructor(
    private val bookRepository: BookRepository
) {
    suspend operator fun invoke(bookId: String): Result<List<BookCharacter>> {
        // Мы уже получаем персонажей в getBookDetails, так что просто извлечем их оттуда.
        return bookRepository.getBookDetails(bookId).map { it.bookCharacters }
    }
}

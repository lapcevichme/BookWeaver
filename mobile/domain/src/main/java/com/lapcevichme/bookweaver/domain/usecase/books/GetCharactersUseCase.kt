package com.lapcevichme.bookweaver.domain.usecase.books

import com.lapcevichme.bookweaver.domain.model.BookCharacter
import com.lapcevichme.bookweaver.domain.repository.BookRepository
import javax.inject.Inject

class GetCharactersUseCase @Inject constructor(
    private val repository: BookRepository
) {
    suspend operator fun invoke(bookId: String): Result<List<BookCharacter>> {
        return repository.getCharacters(bookId)
    }
}
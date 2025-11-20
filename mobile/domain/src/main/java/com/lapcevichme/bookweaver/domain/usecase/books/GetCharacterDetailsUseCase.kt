package com.lapcevichme.bookweaver.domain.usecase.books

import com.lapcevichme.bookweaver.domain.model.BookCharacter
import com.lapcevichme.bookweaver.domain.repository.BookRepository
import javax.inject.Inject

class GetCharacterDetailsUseCase @Inject constructor(
    private val repository: BookRepository
) {
    suspend operator fun invoke(bookId: String, characterId: String): Result<BookCharacter> {
        return repository.getCharacterDetails(bookId, characterId)
    }
}
package com.lapcevichme.bookweaver.domain.usecase.books

import com.lapcevichme.bookweaver.domain.model.BookCharacter
import com.lapcevichme.bookweaver.domain.repository.BookRepository
import java.util.UUID
import javax.inject.Inject

/**
 * Use case для получения полной информации о конкретном персонаже.
 */
class GetCharacterDetailsUseCase @Inject constructor(
    private val bookRepository: BookRepository
) {
    suspend operator fun invoke(bookId: String, characterId: String): Result<BookCharacter> {
        return bookRepository.getBookDetails(bookId).mapCatching { bookDetails ->
            val characterUUID = UUID.fromString(characterId)
            bookDetails.bookCharacters.find { it.id == characterUUID }
                ?: throw NoSuchElementException("Персонаж с ID $characterId не найден в книге $bookId")
        }
    }
}

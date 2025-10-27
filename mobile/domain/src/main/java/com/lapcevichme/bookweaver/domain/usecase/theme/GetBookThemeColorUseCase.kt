package com.lapcevichme.bookweaver.domain.usecase.theme

import com.lapcevichme.bookweaver.domain.repository.BookRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * "Чистый" UseCase для получения потока с цветом
 * для КОНКРЕТНОЙ книги.
 */
class GetBookThemeColorUseCase @Inject constructor(
    private val bookRepository: BookRepository
) {
    operator fun invoke(bookId: String?): Flow<Int?> {
        if (bookId == null) {
            return kotlinx.coroutines.flow.flowOf(null)
        }
        return bookRepository.getBookThemeColorFlow(bookId)
    }
}

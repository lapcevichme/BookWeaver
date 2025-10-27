package com.lapcevichme.bookweaver.domain.usecase.theme

import com.lapcevichme.bookweaver.domain.repository.BookRepository
import javax.inject.Inject

/**
 * "Чистый" UseCase для запуска генерации и кэширования
 * цвета для книги.
 */
class GenerateBookThemeUseCase @Inject constructor(
    private val bookRepository: BookRepository
) {
    suspend operator fun invoke(bookId: String, coverPath: String?) {
        bookRepository.generateAndCacheThemeColor(bookId, coverPath)
    }
}

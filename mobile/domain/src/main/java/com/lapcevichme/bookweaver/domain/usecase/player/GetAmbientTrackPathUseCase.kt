package com.lapcevichme.bookweaver.domain.usecase.player

import com.lapcevichme.bookweaver.domain.repository.BookRepository
import javax.inject.Inject

/**
 * UseCase для получения полного пути к файлу эмбиента по имени.
 */
class GetAmbientTrackPathUseCase @Inject constructor(
    private val bookRepository: BookRepository
) {
    /**
     * @param bookId Текущий ID книги
     * @param ambientName Имя эмбиент-трека (например, "forest.mp3")
     * @return Result с путем к файлу или null
     */
    suspend operator fun invoke(bookId: String, ambientName: String): Result<String?> {
        return bookRepository.getAmbientTrackPath(bookId, ambientName)
    }
}

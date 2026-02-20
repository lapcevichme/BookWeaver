package com.lapcevichme.bookweaver.domain.usecase.player

import com.lapcevichme.bookweaver.domain.repository.BookRepository
import javax.inject.Inject

class GetAmbientTrackPathUseCase @Inject constructor(
    private val bookRepository: BookRepository
) {
    suspend operator fun invoke(bookId: String, ambientName: String): Result<String?> {
        return bookRepository.getAmbientTrackPath(bookId, ambientName)
    }
}

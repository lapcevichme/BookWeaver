package com.lapcevichme.bookweaver.domain.usecase.player

import com.lapcevichme.bookweaver.domain.repository.BookRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetActiveChapterFlowUseCase @Inject constructor(
    private val bookRepository: BookRepository
) {
    operator fun invoke(): Flow<String?> {
        return bookRepository.getActiveChapterIdFlow()
    }
}

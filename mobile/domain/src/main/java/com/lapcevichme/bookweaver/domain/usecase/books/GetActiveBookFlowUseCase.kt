package com.lapcevichme.bookweaver.domain.usecase.books

import com.lapcevichme.bookweaver.domain.repository.BookRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetActiveBookFlowUseCase @Inject constructor(
    private val bookRepository: BookRepository
) {
    operator fun invoke(): Flow<String?> {
        return bookRepository.getActiveBookIdFlow()
    }
}

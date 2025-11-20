package com.lapcevichme.bookweaver.domain.usecase.books

import com.lapcevichme.bookweaver.domain.repository.BookRepository
import javax.inject.Inject

/**
 * UseCase, который запускает принудительную
 * синхронизацию с удаленным сервером.
 */
class SyncLibraryUseCase @Inject constructor(
    private val bookRepository: BookRepository
) {
    suspend operator fun invoke(): Result<Unit> {
        return bookRepository.syncLibraryWithRemote()
    }
}
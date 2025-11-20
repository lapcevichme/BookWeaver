package com.lapcevichme.bookweaver.domain.usecase.books

import com.lapcevichme.bookweaver.domain.model.DownloadProgress
import com.lapcevichme.bookweaver.domain.repository.BookRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * UseCase для запуска скачивания одной главы.
 * Возвращает Flow, по которому можно следить за прогрессом.
 */
class DownloadChapterUseCase @Inject constructor(
    private val bookRepository: BookRepository
) {
    operator fun invoke(bookId: String, chapterId: String): Flow<DownloadProgress> {
        return bookRepository.downloadChapter(bookId, chapterId)
    }
}
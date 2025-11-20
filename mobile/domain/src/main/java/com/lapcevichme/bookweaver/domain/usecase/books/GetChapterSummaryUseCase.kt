package com.lapcevichme.bookweaver.domain.usecase.books

import com.lapcevichme.bookweaver.domain.model.ChapterSummary
import com.lapcevichme.bookweaver.domain.repository.BookRepository
import javax.inject.Inject

class GetChapterSummaryUseCase @Inject constructor(
    private val repository: BookRepository
) {
    suspend operator fun invoke(bookId: String, chapterId: String): Result<ChapterSummary> {
        return repository.getChapterSummary(bookId, chapterId)
    }
}
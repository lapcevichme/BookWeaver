package com.lapcevichme.bookweaver.domain.usecase.books

import com.lapcevichme.bookweaver.domain.model.ScenarioEntry
import com.lapcevichme.bookweaver.domain.repository.BookRepository
import javax.inject.Inject

class GetChapterScenarioUseCase @Inject constructor(
    private val repository: BookRepository
) {
    suspend operator fun invoke(bookId: String, chapterId: String): Result<List<ScenarioEntry>> {
        return repository.getScenarioForChapter(bookId, chapterId)
    }
}

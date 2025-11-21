package com.lapcevichme.bookweaver.domain.usecase.books

import com.lapcevichme.bookweaver.domain.repository.BookRepository
import javax.inject.Inject

class GetChapterOriginalTextUseCase @Inject constructor(
    private val repository: BookRepository
) {
    suspend operator fun invoke(bookId: String, chapterId: String): Result<String> {
        return repository.getChapterOriginalText(bookId, chapterId)
    }
}
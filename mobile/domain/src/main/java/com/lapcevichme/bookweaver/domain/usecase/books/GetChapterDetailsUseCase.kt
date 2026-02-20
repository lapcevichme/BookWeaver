package com.lapcevichme.bookweaver.domain.usecase.books

import com.lapcevichme.bookweaver.domain.model.ChapterDetails
import com.lapcevichme.bookweaver.domain.repository.BookRepository
import javax.inject.Inject

class GetChapterDetailsUseCase @Inject constructor(
    private val bookRepository: BookRepository
) {
    suspend operator fun invoke(bookId: String, chapterId: String): Result<ChapterDetails> {
        return try {
            val scenarioResult = bookRepository.getScenarioForChapter(bookId, chapterId)
            val originalTextResult = bookRepository.getChapterOriginalText(bookId, chapterId)
            val dataPath = bookRepository.getChapterDataPath(bookId, chapterId)

            if (scenarioResult.isFailure || originalTextResult.isFailure) {
                throw scenarioResult.exceptionOrNull()
                    ?: originalTextResult.exceptionOrNull()
                    ?: Exception("Не удалось загрузить основные данные главы")
            }

            val summary = bookRepository.getBookDetails(bookId)
                .getOrNull()
                ?.summaries?.get(chapterId)

            val chapterDetails = ChapterDetails(
                summary = summary,
                scenario = scenarioResult.getOrThrow(),
                originalText = originalTextResult.getOrThrow(),
                dataPath = dataPath
            )
            Result.success(chapterDetails)

        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}


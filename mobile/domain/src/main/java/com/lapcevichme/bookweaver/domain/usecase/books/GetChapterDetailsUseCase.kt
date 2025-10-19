package com.lapcevichme.bookweaver.domain.usecase.books

import com.lapcevichme.bookweaver.domain.model.ChapterDetails
import com.lapcevichme.bookweaver.domain.repository.BookRepository
import javax.inject.Inject

/**
 * Use case для получения полной информации о конкретной главе.
 */
class GetChapterDetailsUseCase @Inject constructor(
    private val bookRepository: BookRepository
) {
    suspend operator fun invoke(bookId: String, chapterId: String): Result<ChapterDetails> {
        return try {
            // Запрашиваем критически важные данные
            val scenarioResult = bookRepository.getScenarioForChapter(bookId, chapterId)
            val originalTextResult = bookRepository.getChapterOriginalText(bookId, chapterId)

            // Если не удалось загрузить что-то из основного, возвращаем ошибку
            if (scenarioResult.isFailure || originalTextResult.isFailure) {
                throw scenarioResult.exceptionOrNull()
                    ?: originalTextResult.exceptionOrNull()
                    ?: Exception("Не удалось загрузить основные данные главы")
            }

            // Запрашиваем опциональные данные (сводку)
            // getOrNull вернет детали книги или null в случае ошибки, не прерывая выполнение
            val summary = bookRepository.getBookDetails(bookId)
                .getOrNull()
                ?.summaries?.get(chapterId)

            // Собираем все в одну модель
            val chapterDetails = ChapterDetails(
                summary = summary, // summary будет null, если его не удалось загрузить
                scenario = scenarioResult.getOrThrow(),
                originalText = originalTextResult.getOrThrow()
            )
            Result.success(chapterDetails)

        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}


package com.lapcevichme.bookweaver.domain.usecase.player

import com.lapcevichme.bookweaver.domain.repository.BookRepository
import javax.inject.Inject

/**
 * Use case для установки активной главы.
 */
class SetActiveChapterUseCase @Inject constructor(
    private val bookRepository: BookRepository
) {
    suspend operator fun invoke(chapterId: String) {
        bookRepository.setActiveChapterId(chapterId)
    }
}

package com.lapcevichme.bookweaver.domain.usecase.books


import com.lapcevichme.bookweaver.domain.model.DownloadProgress
import com.lapcevichme.bookweaver.domain.repository.BookRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Use case для скачивания и установки книги из .bw архива по URL.
 */
class DownloadAndInstallBookUseCase @Inject constructor(
    private val repository: BookRepository
) {
    operator fun invoke(url: String): Flow<DownloadProgress> {
        return repository.downloadAndInstallBook(url)
    }
}

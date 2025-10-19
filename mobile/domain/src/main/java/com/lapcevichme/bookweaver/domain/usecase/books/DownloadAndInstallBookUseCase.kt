package com.lapcevichme.bookweaver.domain.usecase.books


import com.lapcevichme.bookweaver.domain.repository.BookRepository
import java.io.File
import javax.inject.Inject

/**
 * Use case для скачивания и установки книги из .bw архива по URL.
 */
class DownloadAndInstallBookUseCase @Inject constructor(
    private val bookRepository: BookRepository
) {
    /**
     * @param url Прямая ссылка на .bw файл.
     * @return Result, содержащий либо File (путь к новой папке с книгой), либо ошибку.
     */
    suspend operator fun invoke(url: String): Result<File> {
        // Простая валидация URL
        if (!url.startsWith("http") || !url.endsWith(".bw")) {
            return Result.failure(IllegalArgumentException("Неверный URL. Укажите прямую ссылку на .bw файл."))
        }
        return bookRepository.downloadAndInstallBook(url)
    }
}

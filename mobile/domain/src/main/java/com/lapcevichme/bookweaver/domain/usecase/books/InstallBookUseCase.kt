package com.lapcevichme.bookweaver.domain.usecase.books

import com.lapcevichme.bookweaver.domain.repository.BookRepository
import java.io.File
import java.io.InputStream
import javax.inject.Inject

/**
 * Use case для установки книги из потока данных.
 */
class InstallBookUseCase @Inject constructor(
    private val bookRepository: BookRepository
) {
    /**
     * @param inputStream Поток данных из .bw архива.
     * @return Result, содержащий либо File (путь к новой папке с книгой), либо ошибку.
     */
    suspend operator fun invoke(inputStream: InputStream): Result<File> {
        return bookRepository.installBook(inputStream)
    }
}

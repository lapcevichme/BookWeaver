package com.lapcevichme.bookweaver.domain.usecase.books

import com.lapcevichme.bookweaver.domain.repository.BookRepository
import java.io.File
import java.io.InputStream
import javax.inject.Inject

class InstallBookUseCase @Inject constructor(
    private val bookRepository: BookRepository
) {
    suspend operator fun invoke(inputStream: InputStream): Result<File> {
        return bookRepository.installBook(inputStream)
    }
}

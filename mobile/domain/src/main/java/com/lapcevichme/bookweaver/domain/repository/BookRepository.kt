package com.lapcevichme.bookweaver.domain.repository

import com.lapcevichme.bookweaver.domain.model.Book
import kotlinx.coroutines.flow.StateFlow

interface BookRepository {
    val books: StateFlow<List<Book>>
    fun requestBookList()
}
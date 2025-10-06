package com.lapcevichme.bookweaver.data.network.dto

import com.lapcevichme.bookweaver.domain.model.Book
import kotlinx.serialization.Serializable

@Serializable
data class BookDto(
    val title: String,
    val author: String,
    val filePath: String
)

fun BookDto.toDomain(): Book {
    return Book(
        title = this.title,
        author = this.author,
        filePath = this.filePath
    )
}
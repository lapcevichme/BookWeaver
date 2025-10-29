package com.lapcevichme.bookweaver.data.database

import com.lapcevichme.bookweaver.domain.model.Book

/**
 * Маппер из Entity (база данных) в Book (доменная модель).
 */
fun BookEntity.toDomain(): Book {
    return Book(
        id = this.id,
        title = this.title,
        author = this.author,
        localPath = this.localPath,
        coverPath = this.coverPath
    )
}

/**
 * Маппер из Book (доменная модель) в Entity (база данных).
 */
fun Book.toEntity(): BookEntity {
    return BookEntity(
        id = this.id,
        title = this.title,
        author = this.author,
        localPath = this.localPath,
        coverPath = this.coverPath
    )
}

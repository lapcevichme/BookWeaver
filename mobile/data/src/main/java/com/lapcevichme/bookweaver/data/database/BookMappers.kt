package com.lapcevichme.bookweaver.data.database

import com.lapcevichme.bookweaver.data.database.entities.BookEntity
import com.lapcevichme.bookweaver.domain.model.Book

fun BookEntity.toDomain(): Book {
    return Book(
        id = this.id,
        title = this.title,
        author = this.author,
        source = this.source,
        localPath = this.localPath,
        coverPath = this.coverPath
    )
}

fun Book.toEntity(
    bookEntity: BookEntity?
): BookEntity {
    return BookEntity(
        id = this.id,
        title = this.title,
        author = this.author,
        source = this.source,
        localPath = this.localPath,
        coverPath = this.coverPath,
        themeColor = bookEntity?.themeColor,
        lastListenedChapterId = bookEntity?.lastListenedChapterId,
        lastListenedPosition = bookEntity?.lastListenedPosition ?: 0L,
        serverHost = bookEntity?.serverHost,
        remoteCoverUrl = bookEntity?.remoteCoverUrl,
        remoteManifestVersion = bookEntity?.remoteManifestVersion
    )
}

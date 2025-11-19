package com.lapcevichme.bookweaver.data.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.lapcevichme.bookweaver.domain.model.BookSource

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val author: String?,
    val themeColor: Int? = null,
    val lastListenedChapterId: String? = null,
    val lastListenedPosition: Long = 0L,

    val source: BookSource,
    val serverHost: String?,
    val remoteCoverUrl: String?,
    val remoteManifestVersion: Int?,

    val localPath: String?,
    val coverPath: String?
)
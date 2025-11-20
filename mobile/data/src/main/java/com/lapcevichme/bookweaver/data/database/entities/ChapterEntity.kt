package com.lapcevichme.bookweaver.data.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import com.lapcevichme.bookweaver.domain.model.DownloadState

@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ChapterEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo(index = true)
    val bookId: String,

    val title: String,
    val downloadState: DownloadState = DownloadState.NOT_DOWNLOADED,

    val localAudioPath: String?,
    val localScenarioPath: String?,
    val localSubtitlesPath: String?,
    val localOriginalTextPath: String?,

    val chapterIndex: Int = 0,
    val remoteVersion: Int = 1
)
package com.lapcevichme.bookweaver.data.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
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
    ],
    indices = [Index(value = ["bookId"])]
)
data class ChapterEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val title: String,
    val chapterIndex: Int,
    val downloadState: DownloadState = DownloadState.NOT_DOWNLOADED,
    val localAudioPath: String? = null,
    val localScenarioPath: String? = null,
    val localSubtitlesPath: String? = null,
    val localOriginalTextPath: String? = null,
    val remoteVersion: Int = 1,
    val hasAudio: Boolean = false
)
package com.lapcevichme.bookweaver.data.database

import androidx.room.TypeConverter
import com.lapcevichme.bookweaver.domain.model.BookSource
import com.lapcevichme.bookweaver.domain.model.DownloadState

class DatabaseConverters {

    @TypeConverter
    fun fromBookSource(source: BookSource?): String? = source?.name

    @TypeConverter
    fun toBookSource(name: String?): BookSource? =
        name?.let { enumValueOf<BookSource>(it) }

    @TypeConverter
    fun fromDownloadState(state: DownloadState?): String? = state?.name

    @TypeConverter
    fun toDownloadState(name: String?): DownloadState? =
        name?.let { enumValueOf<DownloadState>(it) } ?: DownloadState.NOT_DOWNLOADED
}
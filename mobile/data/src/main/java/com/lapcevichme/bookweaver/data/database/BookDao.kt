package com.lapcevichme.bookweaver.data.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.lapcevichme.bookweaver.data.database.entities.BookEntity
import com.lapcevichme.bookweaver.data.database.entities.ChapterEntity
import com.lapcevichme.bookweaver.domain.model.DownloadState
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    // --- Book Methods ---

    @Query("SELECT * FROM books ORDER BY title ASC")
    fun getAllBooks(): Flow<List<BookEntity>>

    @Upsert
    suspend fun upsertBook(book: BookEntity)

    @Query("DELETE FROM books WHERE id = :bookId")
    suspend fun deleteBook(bookId: String)

    @Query("SELECT themeColor FROM books WHERE id = :bookId")
    fun getBookThemeColor(bookId: String): Flow<Int?>

    @Query("UPDATE books SET themeColor = :color WHERE id = :bookId")
    suspend fun updateThemeColor(bookId: String, color: Int)

    @Query("UPDATE books SET lastListenedChapterId = :chapterId, lastListenedPosition = :position WHERE id = :bookId")
    suspend fun updateListenProgress(bookId: String, chapterId: String, position: Long)

    @Query("SELECT * FROM books WHERE id = :bookId LIMIT 1")
    suspend fun getBookById(bookId: String): BookEntity?

    @Query("UPDATE books SET source = 'SERVER', serverHost = :serverHost, remoteCoverUrl = :remoteCoverUrl, remoteManifestVersion = :remoteVersion WHERE id = :bookId")
    suspend fun updateBookSource(bookId: String, serverHost: String, remoteCoverUrl: String?, remoteVersion: Int)

    @Query("UPDATE books SET title = :newTitle, author = :newAuthor, remoteManifestVersion = :newVersion WHERE id = :bookId")
    suspend fun updateBookMetadata(bookId: String, newTitle: String, newAuthor: String?, newVersion: Int)

    // --- Chapter Methods ---

    @Upsert
    suspend fun upsertChapters(chapters: List<ChapterEntity>)

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY chapterIndex ASC")
    fun getChaptersForBook(bookId: String): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE id = :chapterId LIMIT 1")
    suspend fun getChapter(chapterId: String): ChapterEntity?

    @Query(
        """
        UPDATE chapters 
        SET 
            downloadState = :state, 
            localAudioPath = :localAudioPath, 
            localScenarioPath = :localScenarioPath, 
            localSubtitlesPath = :localSubtitlesPath,
            localOriginalTextPath = :localOriginalTextPath,
            hasAudio = :hasAudio
        WHERE id = :chapterId
        """
    )
    suspend fun updateChapterDownloadPaths(
        chapterId: String,
        state: DownloadState,
        localAudioPath: String?,
        localScenarioPath: String?,
        localSubtitlesPath: String?,
        localOriginalTextPath: String?,
        hasAudio: Boolean
    )

    @Query("UPDATE chapters SET downloadState = :state WHERE id = :chapterId")
    suspend fun updateChapterDownloadState(chapterId: String, state: DownloadState)
}
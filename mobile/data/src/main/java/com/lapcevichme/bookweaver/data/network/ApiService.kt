package com.lapcevichme.bookweaver.data.network

import com.lapcevichme.bookweaver.data.network.dto.BookManifestDto
import com.lapcevichme.bookweaver.data.network.dto.BookStructureResponseDto
import com.lapcevichme.bookweaver.data.network.dto.ChapterInfoDto
import com.lapcevichme.bookweaver.data.network.dto.CharacterDto
import com.lapcevichme.bookweaver.data.network.dto.CharacterListItemDto
import com.lapcevichme.bookweaver.data.network.dto.PingResponseDto
import com.lapcevichme.bookweaver.data.network.dto.PlaybackDataResponseDto
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Streaming

interface ApiService {

    @GET("/api/ping")
    suspend fun ping(): PingResponseDto

    @GET("/api/books")
    suspend fun getBookList(): List<BookManifestDto>

    @GET("/api/books/{bookId}/structure")
    suspend fun getBookStructure(@Path("bookId") bookId: String): BookStructureResponseDto

    @GET("/api/books/{bookId}/characters")
    suspend fun getBookCharacters(@Path("bookId") bookId: String): List<CharacterListItemDto>

    @GET("/api/books/{bookId}/characters/{characterId}")
    suspend fun getCharacterDetails(
        @Path("bookId") bookId: String,
        @Path("characterId") characterId: String
    ): CharacterDto

    @GET("/api/books/{bookId}/chapters/{chapterId}/info")
    suspend fun getChapterInfo(
        @Path("bookId") bookId: String,
        @Path("chapterId") chapterId: String
    ): ChapterInfoDto

    @GET("/api/books/{bookId}/{chapterId}/playbackData")
    suspend fun getPlaybackData(
        @Path("bookId") bookId: String,
        @Path("chapterId") chapterId: String
    ): PlaybackDataResponseDto

    @GET("/download/book/{bookId}.bw")
    @Streaming
    suspend fun downloadBookZip(@Path("bookId") bookId: String): Response<ResponseBody>

    @GET("/download/chapter/{bookId}/{chapterId}.zip")
    @Streaming
    suspend fun downloadChapterZip(
        @Path("bookId") bookId: String,
        @Path("chapterId") chapterId: String
    ): Response<ResponseBody>

    @GET("/api/books/{bookId}/{chapterId}/originalText")
    suspend fun getOriginalText(
        @Path("bookId") bookId: String,
        @Path("chapterId") chapterId: String
    ): ResponseBody
}
package com.lapcevichme.bookweaver.data.repository

import com.lapcevichme.bookweaver.data.network.ApiService
import com.lapcevichme.bookweaver.data.network.dto.BookManifestDto
import com.lapcevichme.bookweaver.data.network.dto.BookStructureResponseDto
import com.lapcevichme.bookweaver.data.network.dto.ChapterInfoDto
import com.lapcevichme.bookweaver.data.network.dto.CharacterDto
import com.lapcevichme.bookweaver.data.network.dto.CharacterListItemDto
import com.lapcevichme.bookweaver.data.network.mapper.toDomain
import com.lapcevichme.bookweaver.domain.model.PlaybackEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import retrofit2.Response
import javax.inject.Inject

class RemoteDataSource @Inject constructor(
    private val apiService: ApiService
) {
    suspend fun fetchBookList(): Result<List<BookManifestDto>> = withContext(Dispatchers.IO) {
        try {
            Result.success(apiService.getBookList())
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    suspend fun fetchBookStructure(bookId: String): Result<BookStructureResponseDto> =
        withContext(Dispatchers.IO) {
            try {
                Result.success(apiService.getBookStructure(bookId))
            } catch (e: Exception) {
                e.printStackTrace()
                Result.failure(e)
            }
        }

    suspend fun fetchBookCharacters(bookId: String): Result<List<CharacterListItemDto>> =
        withContext(Dispatchers.IO) {
            try {
                Result.success(apiService.getBookCharacters(bookId))
            } catch (e: Exception) {
                e.printStackTrace()
                Result.failure(e)
            }
        }

    suspend fun fetchCharacterDetails(bookId: String, characterId: String): Result<CharacterDto> =
        withContext(Dispatchers.IO) {
            try {
                Result.success(apiService.getCharacterDetails(bookId, characterId))
            } catch (e: Exception) {
                e.printStackTrace()
                Result.failure(e)
            }
        }

    suspend fun fetchChapterInfo(bookId: String, chapterId: String): Result<ChapterInfoDto> =
        withContext(Dispatchers.IO) {
            try {
                Result.success(apiService.getChapterInfo(bookId, chapterId))
            } catch (e: Exception) {
                e.printStackTrace()
                Result.failure(e)
            }
        }

    suspend fun fetchPlaybackData(
        bookId: String,
        chapterId: String
    ): Result<Pair<List<PlaybackEntry>, String>> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.getPlaybackData(bookId, chapterId)
            // Маппим новый syncMap в старый добрый List<PlaybackEntry>
            val domainEntries = response.syncMap.map { it.toDomain() }
            // Возвращаем audioUrl вместо audioBaseUrl
            Result.success(Pair(domainEntries, response.audioUrl))
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    suspend fun downloadChapterZip(
        bookId: String,
        chapterId: String
    ): Result<Response<ResponseBody>> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.downloadChapterZip(bookId, chapterId)
            if (!response.isSuccessful) {
                throw Exception("Failed to download chapter: ${response.code()} ${response.message()}")
            }
            Result.success(response)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    suspend fun fetchOriginalText(bookId: String, chapterId: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val response = apiService.getOriginalText(bookId, chapterId)
                if (response.contentLength() == 0L) {
                    return@withContext Result.success("")
                }
                Result.success(response.string())
            } catch (e: Exception) {
                e.printStackTrace()
                Result.failure(e)
            }
        }
}
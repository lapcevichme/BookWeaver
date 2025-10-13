package com.lapcevichme.bookweaverdesktop.backend

import com.lapcevichme.bookweaverdesktop.model.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.statement.HttpResponse
import io.ktor.http.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File

class ApiClient(private val httpClient: HttpClient) {

    private val baseApiUrl = "http://127.0.0.1:8000/api/v1"
    private val healthUrl = "http://127.0.0.1:8000/health"

    // --- Health Check & Task Status ---

    /**
     * ИСПРАВЛЕНИЕ: Используем корректную модель ServerStatus из ApiModels.kt,
     * которая соответствует схеме OpenAPI.
     */
    suspend fun healthCheck(): Result<ServerStatus> {
        return runCatching {
            httpClient.get(healthUrl).body<ServerStatus>()
        }
    }

    suspend fun getTaskStatus(taskId: String): Result<TaskStatusResponse> {
        return runCatching {
            httpClient.get("$baseApiUrl/tasks/$taskId/status").body<TaskStatusResponse>()
        }
    }

    // --- Project & File Endpoints ---

    suspend fun getProjects(): Result<List<String>> {
        return runCatching {
            httpClient.get("$baseApiUrl/projects").body<List<String>>()
        }
    }

    suspend fun getProjectDetails(bookName: String): Result<ProjectDetailsResponse> {
        return runCatching {
            httpClient.get("$baseApiUrl/projects/$bookName").body<ProjectDetailsResponse>()
        }
    }

    /**
     * ИСПРАВЛЕНИЕ: Ожидаем JsonElement вместо JsonObject, так как ответ
     * может быть и объектом, и массивом.
     * ИСПРАВЛЕНИЕ: Используем .name.lowercase() для соответствия URL в OpenAPI.
     */
    suspend fun getBookArtifact(bookName: String, artifact: BookArtifactName): Result<JsonElement> {
        return runCatching {
            val artifactPath = artifact.name.lowercase()
            httpClient.get("$baseApiUrl/projects/$bookName/artifacts/$artifactPath").body<JsonElement>()
        }
    }

    /**
     * ИСПРАВЛЕНИЕ: Ожидаем JsonElement вместо JsonObject.
     * ИСПРАВЛЕНИЕ: Используем .name.lowercase() для соответствия URL в OpenAPI.
     */
    suspend fun getChapterArtifact(
        bookName: String,
        volumeNum: Int,
        chapterNum: Int,
        artifact: ChapterArtifactName
    ): Result<JsonElement> {
        return runCatching {
            val artifactPath = artifact.name.lowercase()
            val url = "$baseApiUrl/projects/$bookName/chapters/$volumeNum/$chapterNum/artifacts/$artifactPath"
            httpClient.get(url).body<JsonElement>()
        }
    }

    suspend fun updateBookArtifact(
        bookName: String,
        artifact: BookArtifactName,
        content: JsonObject
    ): Result<HttpResponse> {
        return runCatching {
            val artifactPath = artifact.name.lowercase()
            httpClient.post("$baseApiUrl/projects/$bookName/artifacts/$artifactPath") {
                contentType(ContentType.Application.Json)
                setBody(content)
            }
        }
    }


    // --- AI Task Endpoints ---

    suspend fun startCharacterAnalysis(request: BookTaskRequest): Result<TaskStatusResponse> {
        return runCatching {
            httpClient.post("$baseApiUrl/analyze_characters") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body<TaskStatusResponse>()
        }
    }

    suspend fun startSummaryGeneration(request: BookTaskRequest): Result<TaskStatusResponse> {
        return runCatching {
            httpClient.post("$baseApiUrl/generate_summaries") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body<TaskStatusResponse>()
        }
    }

    suspend fun startScenarioGeneration(request: ChapterTaskRequest): Result<TaskStatusResponse> {
        return runCatching {
            httpClient.post("$baseApiUrl/generate_scenario") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body<TaskStatusResponse>()
        }
    }

    suspend fun startTtsSynthesis(request: ChapterTaskRequest): Result<TaskStatusResponse> {
        return runCatching {
            httpClient.post("$baseApiUrl/synthesize_tts") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body<TaskStatusResponse>()
        }
    }

    suspend fun startVoiceConversion(request: ChapterTaskRequest): Result<TaskStatusResponse> {
        return runCatching {
            httpClient.post("$baseApiUrl/apply_voice_conversion") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body<TaskStatusResponse>()
        }
    }

    suspend fun importBook(file: File): Result<HttpResponse> {
        return runCatching {
            httpClient.post("$baseApiUrl/projects/import") {
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append("file", file.readBytes(), Headers.build {
                                append(HttpHeaders.ContentType, ContentType.Application.OctetStream)
                                append(HttpHeaders.ContentDisposition, "filename=\"${file.name}\"")
                            })
                        }
                    )
                )
            }
        }
    }
}

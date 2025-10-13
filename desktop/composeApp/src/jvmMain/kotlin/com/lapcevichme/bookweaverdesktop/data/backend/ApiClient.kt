package com.lapcevichme.bookweaverdesktop.data.backend

import com.lapcevichme.bookweaverdesktop.data.model.BookArtifact
import com.lapcevichme.bookweaverdesktop.data.model.BookTaskRequest
import com.lapcevichme.bookweaverdesktop.data.model.ChapterArtifact
import com.lapcevichme.bookweaverdesktop.data.model.ChapterTaskRequest
import com.lapcevichme.bookweaverdesktop.data.model.ProjectDetails
import com.lapcevichme.bookweaverdesktop.data.model.ServerStatus
import com.lapcevichme.bookweaverdesktop.data.model.TaskStatus
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.JsonElement
import java.io.File

class ApiClient(private val httpClient: HttpClient) {

    private val baseApiUrl = "http://127.0.0.1:8000/api/v1"
    private val healthUrl = "http://127.0.0.1:8000/health"

    // --- Health Check и статус задач ---

    suspend fun healthCheck(): Result<ServerStatus> {
        return runCatching {
            httpClient.get(healthUrl).body<ServerStatus>()
        }
    }

    suspend fun getTaskStatus(taskId: String): Result<TaskStatus> {
        return runCatching {
            httpClient.get("$baseApiUrl/tasks/$taskId/status").body<TaskStatus>()
        }
    }

    // --- Эндпоинты проектов и файлов ---

    suspend fun getProjects(): Result<List<String>> {
        return runCatching {
            httpClient.get("$baseApiUrl/projects").body<List<String>>()
        }
    }

    suspend fun getProjectDetails(bookName: String): Result<ProjectDetails> {
        return runCatching {
            httpClient.get("$baseApiUrl/projects/$bookName").body<ProjectDetails>()
        }
    }

    suspend fun getBookArtifact(bookName: String, artifact: BookArtifact): Result<JsonElement> {
        return runCatching {
            val artifactPath = artifact.name.lowercase()
            httpClient.get("$baseApiUrl/projects/$bookName/artifacts/$artifactPath").body<JsonElement>()
        }
    }

    suspend fun getChapterArtifact(
        bookName: String,
        volumeNum: Int,
        chapterNum: Int,
        artifact: ChapterArtifact
    ): Result<JsonElement> {
        return runCatching {
            val artifactPath = artifact.name.lowercase()
            val url = "$baseApiUrl/projects/$bookName/chapters/$volumeNum/$chapterNum/artifacts/$artifactPath"
            httpClient.get(url).body<JsonElement>()
        }
    }

    suspend fun updateBookArtifact(
        bookName: String,
        artifact: BookArtifact,
        content: JsonElement
    ): Result<HttpResponse> {
        return runCatching {
            val artifactPath = artifact.name.lowercase()
            httpClient.post("$baseApiUrl/projects/$bookName/artifacts/$artifactPath") {
                contentType(ContentType.Application.Json)
                setBody(content)
            }
        }
    }

    suspend fun updateChapterArtifact(
        bookName: String,
        volumeNum: Int,
        chapterNum: Int,
        artifactName: ChapterArtifact,
        content: JsonElement
    ): Result<HttpResponse> {
        return runCatching {
            val artifactPath = artifactName.name.lowercase()
            httpClient.post("$baseApiUrl/projects/$bookName/chapters/$volumeNum/$chapterNum/artifacts/$artifactPath") {
                contentType(ContentType.Application.Json)
                setBody(content)
            }
        }
    }

    suspend fun importBook(file: File): Result<HttpResponse> {
        return runCatching {
            httpClient.post("$baseApiUrl/projects/import") {
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append("file", file.readBytes(), Headers.build {
                                // Определяем ContentType в зависимости от расширения файла
                                val contentType = when (file.extension.lowercase()) {
                                    "txt" -> ContentType.Text.Plain
                                    "epub" -> ContentType("application", "epub+zip")
                                    else -> ContentType.Application.OctetStream // Общий тип для остальных файлов
                                }
                                append(HttpHeaders.ContentType, contentType)
                                append(HttpHeaders.ContentDisposition, "filename=\"${file.name}\"")
                            })
                        }
                    )
                )
            }
        }
    }

    // --- Эндпоинты AI задач ---

    suspend fun startCharacterAnalysis(request: BookTaskRequest): Result<TaskStatus> {
        return runCatching {
            httpClient.post("$baseApiUrl/analyze_characters") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body<TaskStatus>()
        }
    }

    suspend fun startSummaryGeneration(request: BookTaskRequest): Result<TaskStatus> {
        return runCatching {
            httpClient.post("$baseApiUrl/generate_summaries") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body<TaskStatus>()
        }
    }

    suspend fun startScenarioGeneration(request: ChapterTaskRequest): Result<TaskStatus> {
        return runCatching {
            httpClient.post("$baseApiUrl/generate_scenario") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body<TaskStatus>()
        }
    }

    suspend fun startTtsSynthesis(request: ChapterTaskRequest): Result<TaskStatus> {
        return runCatching {
            httpClient.post("$baseApiUrl/synthesize_tts") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body<TaskStatus>()
        }
    }

    suspend fun startVoiceConversion(request: ChapterTaskRequest): Result<TaskStatus> {
        return runCatching {
            httpClient.post("$baseApiUrl/apply_voice_conversion") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body<TaskStatus>()
        }
    }
}

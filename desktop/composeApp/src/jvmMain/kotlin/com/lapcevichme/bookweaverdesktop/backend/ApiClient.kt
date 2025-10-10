package com.lapcevichme.bookweaverdesktop.backend

import com.lapcevichme.bookweaverdesktop.model.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.statement.HttpResponse
import io.ktor.http.*
import kotlinx.serialization.json.JsonObject
import java.io.File

class ApiClient(private val httpClient: HttpClient) {

    private val baseApiUrl = "http://127.0.0.1:8000/api/v1"
    private val healthUrl = "http://127.0.0.1:8000/health"

    // --- Health Check & Task Status ---

    suspend fun healthCheck(): Result<ServerStatusResponse> {
        return runCatching {
            httpClient.get(healthUrl).body<ServerStatusResponse>()
        }
    }

    suspend fun getTaskStatus(taskId: String): Result<TaskStatusResponse> {
        return runCatching {
            httpClient.get("$baseApiUrl/tasks/$taskId/status").body<TaskStatusResponse>()
        }
    }

    // --- Project & File Endpoints ---

    /**
     * Получает список всех проектов (книг).
     */
    suspend fun getProjects(): Result<List<String>> {
        return runCatching {
            httpClient.get("$baseApiUrl/projects").body<List<String>>()
        }
    }

    /**
     * Получает детальную информацию о проекте, включая статус всех глав.
     */
    suspend fun getProjectDetails(bookName: String): Result<ProjectDetailsResponse> {
        return runCatching {
            httpClient.get("$baseApiUrl/projects/$bookName").body<ProjectDetailsResponse>()
        }
    }

    /**
     * Получает артефакт уровня книги (например, manifest.json).
     */
    suspend fun getBookArtifact(bookName: String, artifact: BookArtifactName): Result<JsonObject> {
        return runCatching {
            httpClient.get("$baseApiUrl/projects/$bookName/artifacts/${artifact.fileName}").body<JsonObject>()
        }
    }

    /**
     * Получает артефакт уровня главы (например, scenario.json).
     */
    suspend fun getChapterArtifact(
        bookName: String,
        volumeNum: Int,
        chapterNum: Int,
        artifact: ChapterArtifactName
    ): Result<JsonObject> {
        return runCatching {
            val url = "$baseApiUrl/projects/$bookName/chapters/$volumeNum/$chapterNum/artifacts/${artifact.fileName}"
            httpClient.get(url).body<JsonObject>()
        }
    }


    // --- AI Task Endpoints ---

    /**
     * Запускает анализ персонажей для всей книги.
     */
    suspend fun startCharacterAnalysis(request: BookTaskRequest): Result<TaskStatusResponse> {
        return runCatching {
            httpClient.post("$baseApiUrl/analyze_characters") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body<TaskStatusResponse>()
        }
    }

    /**
     * Запускает генерацию пересказов для всей книги.
     */
    suspend fun startSummaryGeneration(request: BookTaskRequest): Result<TaskStatusResponse> {
        return runCatching {
            httpClient.post("$baseApiUrl/generate_summaries") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body<TaskStatusResponse>()
        }
    }

    /**
     * Запускает генерацию сценария для одной главы.
     */
    suspend fun startScenarioGeneration(request: ChapterTaskRequest): Result<TaskStatusResponse> {
        return runCatching {
            httpClient.post("$baseApiUrl/generate_scenario") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body<TaskStatusResponse>()
        }
    }

    /**
     * Запускает синтез речи и субтитров для одной главы.
     */
    suspend fun startTtsSynthesis(request: ChapterTaskRequest): Result<TaskStatusResponse> {
        return runCatching {
            httpClient.post("$baseApiUrl/synthesize_tts") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body<TaskStatusResponse>()
        }
    }

    /**
     * Запускает применение эмоциональной окраски для одной главы.
     */
    suspend fun startVoiceConversion(request: ChapterTaskRequest): Result<TaskStatusResponse> {
        return runCatching {
            httpClient.post("$baseApiUrl/apply_voice_conversion") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body<TaskStatusResponse>()
        }
    }


    /**
     * Imports a new book from a file (.txt, .epub) by sending it to the backend for conversion.
     */
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

package com.lapcevichme.bookweaverdesktop.backend

import com.lapcevichme.bookweaverdesktop.model.ChapterTaskRequest
import com.lapcevichme.bookweaverdesktop.model.ServerStatusResponse
import com.lapcevichme.bookweaverdesktop.model.TaskStatusResponse
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*

class ApiClient(private val httpClient: HttpClient) {

    private val baseApiUrl = "http://127.0.0.1:8000/api/v1"
    private val healthUrl = "http://127.0.0.1:8000/health"

    /**
     * Проверяет "здоровье" API сервера, делая запрос к эндпоинту /health.
     */
    suspend fun healthCheck(): Result<ServerStatusResponse> {
        return runCatching {
            httpClient.get(healthUrl).body<ServerStatusResponse>()
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

    suspend fun getTaskStatus(taskId: String): Result<TaskStatusResponse> {
        return runCatching {
            httpClient.get("$baseApiUrl/tasks/$taskId/status").body<TaskStatusResponse>()
        }
    }



    // TODO: Добавить методы для остальных эндпоинтов по аналогии
    // suspend fun startVoiceConversion(...)
    // suspend fun startScenarioGeneration(...)
}


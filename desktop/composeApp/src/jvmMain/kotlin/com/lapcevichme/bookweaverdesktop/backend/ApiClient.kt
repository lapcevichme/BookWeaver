package com.lapcevichme.bookweaverdesktop.backend

import com.lapcevichme.bookweaverdesktop.model.ChapterTaskRequest
import com.lapcevichme.bookweaverdesktop.model.TaskStatusResponse
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*

class ApiClient(private val httpClient: HttpClient) {

    private val baseUrl = "http://127.0.0.1:8000/api/v1"

    suspend fun startTtsSynthesis(request: ChapterTaskRequest): TaskStatusResponse? {
        return try {
            httpClient.post("$baseUrl/synthesize_tts") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body<TaskStatusResponse>()
        } catch (e: Exception) {
            println("Error starting TTS synthesis: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    suspend fun getTaskStatus(taskId: String): TaskStatusResponse? {
        return try {
            httpClient.get("$baseUrl/tasks/$taskId/status").body<TaskStatusResponse>()
        } catch (e: Exception) {
            println("Error getting task status: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    // TODO: Добавить методы для остальных эндпоинтов по аналогии
    // suspend fun startVoiceConversion(...)
    // suspend fun startScenarioGeneration(...)
}


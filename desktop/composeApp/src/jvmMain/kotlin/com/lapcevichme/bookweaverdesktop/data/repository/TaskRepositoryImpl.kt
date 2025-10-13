package com.lapcevichme.bookweaverdesktop.data.repository

import com.lapcevichme.bookweaverdesktop.data.backend.ApiClient
import com.lapcevichme.bookweaverdesktop.data.mapper.toDomainTask
import com.lapcevichme.bookweaverdesktop.data.model.BookTaskRequest
import com.lapcevichme.bookweaverdesktop.data.model.ChapterTaskRequest
import com.lapcevichme.bookweaverdesktop.domain.model.Task
import com.lapcevichme.bookweaverdesktop.domain.repository.TaskRepository

class TaskRepositoryImpl(private val apiClient: ApiClient) : TaskRepository {

    override suspend fun getTaskStatus(taskId: String): Result<Task> {
        return apiClient.getTaskStatus(taskId).map { it.toDomainTask() }
    }

    override suspend fun startCharacterAnalysis(projectName: String): Result<Task> {
        val request = BookTaskRequest(bookName = projectName)
        return apiClient.startCharacterAnalysis(request).map { it.toDomainTask() }
    }

    override suspend fun startSummaryGeneration(projectName: String): Result<Task> {
        val request = BookTaskRequest(bookName = projectName)
        return apiClient.startSummaryGeneration(request).map { it.toDomainTask() }
    }

    override suspend fun startScenarioGeneration(
        projectName: String,
        volumeNumber: Int,
        chapterNumber: Int
    ): Result<Task> {
        val request = ChapterTaskRequest(projectName, volumeNumber, chapterNumber)
        return apiClient.startScenarioGeneration(request).map { it.toDomainTask() }
    }

    override suspend fun startTtsSynthesis(
        projectName: String,
        volumeNumber: Int,
        chapterNumber: Int
    ): Result<Task> {
        val request = ChapterTaskRequest(projectName, volumeNumber, chapterNumber)
        return apiClient.startTtsSynthesis(request).map { it.toDomainTask() }
    }

    override suspend fun startVoiceConversion(
        projectName: String,
        volumeNumber: Int,
        chapterNumber: Int
    ): Result<Task> {
        val request = ChapterTaskRequest(projectName, volumeNumber, chapterNumber)
        return apiClient.startVoiceConversion(request).map { it.toDomainTask() }
    }
}

package com.lapcevichme.bookweaverdesktop.domain.repository

import com.lapcevichme.bookweaverdesktop.domain.model.Task

/**
 * Контракт для управления и отслеживания фоновых AI-задач.
 */
interface TaskRepository {
    suspend fun getTaskStatus(taskId: String): Result<Task>

    suspend fun startCharacterAnalysis(projectName: String): Result<Task>

    suspend fun startSummaryGeneration(projectName: String): Result<Task>

    suspend fun startScenarioGeneration(
        projectName: String,
        volumeNumber: Int,
        chapterNumber: Int
    ): Result<Task>

    suspend fun startTtsSynthesis(
        projectName: String,
        volumeNumber: Int,
        chapterNumber: Int
    ): Result<Task>

    suspend fun startVoiceConversion(
        projectName: String,
        volumeNumber: Int,
        chapterNumber: Int
    ): Result<Task>
}

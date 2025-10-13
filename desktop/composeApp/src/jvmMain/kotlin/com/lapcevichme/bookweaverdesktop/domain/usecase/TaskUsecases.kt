package com.lapcevichme.bookweaverdesktop.domain.usecase

import com.lapcevichme.bookweaverdesktop.domain.repository.TaskRepository

class GetTaskStatusUseCase(private val repository: TaskRepository) {
    suspend operator fun invoke(taskId: String) = repository.getTaskStatus(taskId)
}

class StartCharacterAnalysisUseCase(private val repository: TaskRepository) {
    suspend operator fun invoke(projectName: String) = repository.startCharacterAnalysis(projectName)
}

class StartSummaryGenerationUseCase(private val repository: TaskRepository) {
    suspend operator fun invoke(projectName: String) = repository.startSummaryGeneration(projectName)
}

class StartScenarioGenerationUseCase(private val repository: TaskRepository) {
    suspend operator fun invoke(projectName: String, volumeNumber: Int, chapterNumber: Int) =
        repository.startScenarioGeneration(projectName, volumeNumber, chapterNumber)
}

class StartTtsSynthesisUseCase(private val repository: TaskRepository) {
    suspend operator fun invoke(projectName: String, volumeNumber: Int, chapterNumber: Int) =
        repository.startTtsSynthesis(projectName, volumeNumber, chapterNumber)
}

class StartVoiceConversionUseCase(private val repository: TaskRepository) {
    suspend operator fun invoke(projectName: String, volumeNumber: Int, chapterNumber: Int) =
        repository.startVoiceConversion(projectName, volumeNumber, chapterNumber)
}


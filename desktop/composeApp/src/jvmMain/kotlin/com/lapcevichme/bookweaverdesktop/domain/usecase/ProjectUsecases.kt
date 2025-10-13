package com.lapcevichme.bookweaverdesktop.domain.usecase

import com.lapcevichme.bookweaverdesktop.domain.model.Scenario
import com.lapcevichme.bookweaverdesktop.domain.repository.ProjectRepository
import java.io.File

class GetProjectsUseCase(private val repository: ProjectRepository) {
    suspend operator fun invoke() = repository.getProjects()
}

class GetProjectDetailsUseCase(private val repository: ProjectRepository) {
    suspend operator fun invoke(projectName: String) = repository.getProjectDetails(projectName)
}

class ImportBookUseCase(private val repository: ProjectRepository) {
    suspend operator fun invoke(file: File) = repository.importBook(file)
}

class GetChapterScenarioUseCase(private val repository: ProjectRepository) {
    suspend operator fun invoke(projectName: String, volumeNumber: Int, chapterNumber: Int) =
        repository.getChapterScenario(projectName, volumeNumber, chapterNumber)
}

class UpdateChapterScenarioUseCase(private val repository: ProjectRepository) {
    suspend operator fun invoke(
        projectName: String,
        volumeNumber: Int,
        chapterNumber: Int,
        scenario: Scenario
    ) = repository.updateChapterScenario(projectName, volumeNumber, chapterNumber, scenario)
}

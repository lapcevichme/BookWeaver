package com.lapcevichme.bookweaverdesktop.domain.usecase

import com.lapcevichme.bookweaverdesktop.domain.model.BookArtifact
import com.lapcevichme.bookweaverdesktop.domain.repository.ProjectRepository

class GetBookArtifactUseCase(private val projectRepository: ProjectRepository) {
    suspend operator fun invoke(projectName: String, artifact: BookArtifact): Result<String> {
        return projectRepository.getBookArtifact(projectName, artifact)
    }
}

class UpdateBookArtifactUseCase(private val projectRepository: ProjectRepository) {
    suspend operator fun invoke(projectName: String, artifact: BookArtifact, content: String): Result<Unit> {
        return projectRepository.updateBookArtifact(projectName, artifact, content)
    }
}

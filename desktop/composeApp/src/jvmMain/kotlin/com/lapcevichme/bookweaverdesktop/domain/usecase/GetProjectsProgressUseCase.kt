package com.lapcevichme.bookweaverdesktop.domain.usecase

import com.lapcevichme.bookweaverdesktop.domain.model.ProjectProgress
import com.lapcevichme.bookweaverdesktop.domain.repository.ProjectRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * UseCase для получения списка всех проектов с информацией об их прогрессе.
 * Инкапсулирует логику получения списка проектов и последующего запроса деталей для каждого.
 */
class GetProjectsProgressUseCase(private val repository: ProjectRepository) {
    suspend operator fun invoke(): Result<List<ProjectProgress>> = runCatching {
        coroutineScope {
            val projects = repository.getProjects().getOrThrow()
            projects.map { project ->
                async {
                    repository.getProjectDetails(project.name).map { details ->
                        val total = details.chapters.size
                        val completed = details.chapters.count { it.hasScenario && it.hasAudio }
                        ProjectProgress(project.name, completed, total)
                    }.getOrElse {
                        ProjectProgress(project.name, 0, 0)
                    }
                }
            }.awaitAll()
        }
    }
}


package com.lapcevichme.bookweaverdesktop.domain.repository

import com.lapcevichme.bookweaverdesktop.domain.model.BookArtifact
import com.lapcevichme.bookweaverdesktop.domain.model.ChapterArtifact
import com.lapcevichme.bookweaverdesktop.domain.model.Project
import com.lapcevichme.bookweaverdesktop.domain.model.ProjectDetails
import com.lapcevichme.bookweaverdesktop.domain.model.Scenario
import java.io.File

/**
 * Контракт для работы с данными проектов (книг).
 */
interface ProjectRepository {

    suspend fun getProjects(): Result<List<Project>>

    suspend fun getProjectDetails(projectName: String): Result<ProjectDetails>

    suspend fun importBook(bookFile: File): Result<Unit>

    // В данном случае, артефакты книги (манифест, персонажи) - это просто JSON-строки.
    suspend fun getBookArtifact(projectName: String, artifact: BookArtifact): Result<String>

    // Сценарий - это структурированный объект.
    suspend fun getChapterScenario(
        projectName: String,
        volumeNumber: Int,
        chapterNumber: Int
    ): Result<Scenario>

    // Другие артефакты глав (например, субтитры) могут быть строками.
    suspend fun getChapterArtifact(
        projectName: String,
        volumeNumber: Int,
        chapterNumber: Int,
        artifact: ChapterArtifact
    ): Result<String>

    suspend fun updateBookArtifact(
        projectName: String,
        artifact: BookArtifact,
        content: String
    ): Result<Unit>

    suspend fun updateChapterScenario(
        projectName: String,
        volumeNumber: Int,
        chapterNumber: Int,
        scenario: Scenario
    ): Result<Unit>
}


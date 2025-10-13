package com.lapcevichme.bookweaverdesktop.data.repository

import com.lapcevichme.bookweaverdesktop.data.backend.ApiClient
import com.lapcevichme.bookweaverdesktop.data.mapper.toDataReplicas
import com.lapcevichme.bookweaverdesktop.data.mapper.toDomainProject
import com.lapcevichme.bookweaverdesktop.data.mapper.toDomainProjectDetails
import com.lapcevichme.bookweaverdesktop.data.mapper.toDomainScenario
import com.lapcevichme.bookweaverdesktop.domain.model.*
import com.lapcevichme.bookweaverdesktop.domain.repository.ProjectRepository
import kotlinx.serialization.json.Json
import java.io.File
import com.lapcevichme.bookweaverdesktop.data.model.BookArtifact as DataBookArtifact
import com.lapcevichme.bookweaverdesktop.data.model.ChapterArtifact as DataChapterArtifact
import com.lapcevichme.bookweaverdesktop.data.model.Replica as DataReplica

class ProjectRepositoryImpl(
    private val apiClient: ApiClient,
    private val json: Json
) : ProjectRepository {

    override suspend fun getProjects(): Result<List<Project>> {
        return apiClient.getProjects().map { projectNames ->
            projectNames.map { it.toDomainProject() }
        }
    }

    override suspend fun getProjectDetails(projectName: String): Result<ProjectDetails> {
        return apiClient.getProjectDetails(projectName).map { it.toDomainProjectDetails() }
    }

    override suspend fun importBook(bookFile: File): Result<Unit> {
        return apiClient.importBook(bookFile).map { /* Success maps to Unit */ }
    }

    override suspend fun getBookArtifact(projectName: String, artifact: BookArtifact): Result<String> {
        val dataArtifact = DataBookArtifact.valueOf(artifact.name)
        return apiClient.getBookArtifact(projectName, dataArtifact).map { it.toString() }
    }

    override suspend fun getChapterScenario(
        projectName: String,
        volumeNumber: Int,
        chapterNumber: Int
    ): Result<Scenario> {
        return apiClient.getChapterArtifact(
            projectName,
            volumeNumber,
            chapterNumber,
            DataChapterArtifact.SCENARIO
        ).map { jsonElement ->
            val dataReplicas = json.decodeFromString<List<DataReplica>>(jsonElement.toString())
            dataReplicas.toDomainScenario()
        }
    }

    override suspend fun getChapterArtifact(
        projectName: String,
        volumeNumber: Int,
        chapterNumber: Int,
        artifact: ChapterArtifact
    ): Result<String> {
        val dataArtifact = DataChapterArtifact.valueOf(artifact.name)
        return apiClient.getChapterArtifact(projectName, volumeNumber, chapterNumber, dataArtifact)
            .map { it.toString() }
    }


    override suspend fun updateBookArtifact(
        projectName: String,
        artifact: BookArtifact,
        content: String
    ): Result<Unit> {
        val dataArtifact = DataBookArtifact.valueOf(artifact.name)
        val jsonElement = json.parseToJsonElement(content)
        return apiClient.updateBookArtifact(projectName, dataArtifact, jsonElement).map {}
    }

    override suspend fun updateChapterScenario(
        projectName: String,
        volumeNumber: Int,
        chapterNumber: Int,
        scenario: Scenario
    ): Result<Unit> {
        val dataReplicas = scenario.toDataReplicas()
        val jsonElement = json.parseToJsonElement(json.encodeToString(dataReplicas))
        return apiClient.updateChapterArtifact(
            projectName,
            volumeNumber,
            chapterNumber,
            DataChapterArtifact.SCENARIO,
            jsonElement
        ).map {}
    }
}

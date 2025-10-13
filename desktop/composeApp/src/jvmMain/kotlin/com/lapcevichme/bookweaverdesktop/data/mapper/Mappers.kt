package com.lapcevichme.bookweaverdesktop.data.mapper

import com.lapcevichme.bookweaverdesktop.data.backend.BackendProcessManager
// Data models (DTOs)
import com.lapcevichme.bookweaverdesktop.data.model.ChapterStatus as DataChapterStatus
import com.lapcevichme.bookweaverdesktop.data.model.ProjectDetails as DataProjectDetails
import com.lapcevichme.bookweaverdesktop.data.model.Replica as DataReplica
import com.lapcevichme.bookweaverdesktop.data.model.TaskStatus as DataTaskStatus
import com.lapcevichme.bookweaverdesktop.data.model.TaskStatusEnum as DataTaskStatusEnum
// Domain models
import com.lapcevichme.bookweaverdesktop.domain.model.BackendServerState
import com.lapcevichme.bookweaverdesktop.domain.model.BackendServerStatus
import com.lapcevichme.bookweaverdesktop.domain.model.Chapter as DomainChapter
import com.lapcevichme.bookweaverdesktop.domain.model.Project as DomainProject
import com.lapcevichme.bookweaverdesktop.domain.model.ProjectDetails as DomainProjectDetails
import com.lapcevichme.bookweaverdesktop.domain.model.Replica as DomainReplica
import com.lapcevichme.bookweaverdesktop.domain.model.Scenario as DomainScenario
import com.lapcevichme.bookweaverdesktop.domain.model.Task as DomainTask
import com.lapcevichme.bookweaverdesktop.domain.model.TaskStatus as DomainTaskStatus

// --- Project Mappers ---

fun String.toDomainProject(): DomainProject = DomainProject(name = this)

fun DataProjectDetails.toDomainProjectDetails(): DomainProjectDetails = DomainProjectDetails(
    name = this.bookName,
    chapters = this.chapters.map { it.toDomainChapter() }
)

fun DataChapterStatus.toDomainChapter(): DomainChapter = DomainChapter(
    volumeNumber = this.volumeNum,
    chapterNumber = this.chapterNum,
    hasScenario = this.hasScenario,
    hasSubtitles = this.hasSubtitles,
    hasAudio = this.hasAudio
)

fun List<DataReplica>.toDomainScenario(): DomainScenario = DomainScenario(
    replicas = this.map { it.toDomainReplica() }
)

fun DataReplica.toDomainReplica(): DomainReplica = DomainReplica(
    speaker = this.speaker,
    text = this.text
)

fun DomainScenario.toDataReplicas(): List<DataReplica> =
    this.replicas.map { it.toDataReplica() }

fun DomainReplica.toDataReplica(): DataReplica = DataReplica(
    speaker = this.speaker,
    text = this.text
)

// --- Task Mappers ---

fun DataTaskStatus.toDomainTask(): DomainTask = DomainTask(
    id = this.taskId,
    status = this.status.toDomainTaskStatus(),
    progress = this.progress,
    stage = this.stage,
    message = this.message
)

fun DataTaskStatusEnum.toDomainTaskStatus(): DomainTaskStatus = when (this) {
    DataTaskStatusEnum.QUEUED -> DomainTaskStatus.QUEUED
    DataTaskStatusEnum.PROCESSING -> DomainTaskStatus.PROCESSING
    DataTaskStatusEnum.COMPLETE -> DomainTaskStatus.COMPLETE
    DataTaskStatusEnum.FAILED -> DomainTaskStatus.FAILED
}

// --- Backend State Mapper ---

fun BackendProcessManager.State.toDomainStatus(): BackendServerStatus = when (this) {
    is BackendProcessManager.State.STOPPED -> BackendServerStatus(BackendServerState.READY, "Сервер остановлен")
    is BackendProcessManager.State.STARTING -> BackendServerStatus(BackendServerState.INITIALIZING, "Запуск процесса...")
    is BackendProcessManager.State.RUNNING_INITIALIZING -> BackendServerStatus(BackendServerState.INITIALIZING, "Инициализация API...")
    is BackendProcessManager.State.RUNNING_HEALTHY -> BackendServerStatus(BackendServerState.READY, "Сервер готов к работе")
    is BackendProcessManager.State.FAILED -> BackendServerStatus(BackendServerState.ERROR, this.reason)
}

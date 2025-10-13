package com.lapcevichme.bookweaverdesktop.data.repository

import com.lapcevichme.bookweaverdesktop.data.backend.BackendProcessManager
import com.lapcevichme.bookweaverdesktop.data.mapper.toDomainStatus
import com.lapcevichme.bookweaverdesktop.domain.model.BackendServerStatus
import com.lapcevichme.bookweaverdesktop.domain.repository.BackendRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class BackendRepositoryImpl(
    private val backendProcessManager: BackendProcessManager,
    scope: CoroutineScope
) : BackendRepository {

    override val backendState: StateFlow<BackendServerStatus> =
        backendProcessManager.state
            .map { it.toDomainStatus() }
            .stateIn(
                scope,
                SharingStarted.Eagerly,
                backendProcessManager.state.value.toDomainStatus()
            )

    override val logs: StateFlow<List<String>> = backendProcessManager.logs

    override suspend fun start() {
        backendProcessManager.start()
    }

    override suspend fun stop() {
        backendProcessManager.stop()
    }
}

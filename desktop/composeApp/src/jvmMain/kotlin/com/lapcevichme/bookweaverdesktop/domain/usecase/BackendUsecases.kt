package com.lapcevichme.bookweaverdesktop.domain.usecase

import com.lapcevichme.bookweaverdesktop.domain.repository.BackendRepository

class StartBackendUseCase(private val repository: BackendRepository) {
    suspend operator fun invoke() = repository.start()
}

class StopBackendUseCase(private val repository: BackendRepository) {
    suspend operator fun invoke() = repository.stop()
}

class ObserveBackendStateUseCase(private val repository: BackendRepository) {
    operator fun invoke() = repository.backendState
}

class ObserveBackendLogsUseCase(private val repository: BackendRepository) {
    operator fun invoke() = repository.logs
}

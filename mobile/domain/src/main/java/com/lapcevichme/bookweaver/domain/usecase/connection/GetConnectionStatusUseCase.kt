package com.lapcevichme.bookweaver.domain.usecase.connection

import com.lapcevichme.bookweaver.domain.repository.ServerRepository
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

class GetConnectionStatusUseCase @Inject constructor(
    private val serverRepository: ServerRepository
) {
    operator fun invoke(): StateFlow<String> = serverRepository.connectionStatus
}

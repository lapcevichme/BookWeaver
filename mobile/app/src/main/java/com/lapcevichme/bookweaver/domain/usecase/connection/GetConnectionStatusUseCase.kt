package com.lapcevichme.bookweaver.domain.usecase.connection

import com.lapcevichme.network.ServerRepository
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

class GetConnectionStatusUseCase @Inject constructor(
    private val serverRepository: ServerRepository
) {
    operator fun invoke(): StateFlow<String> = serverRepository.connectionStatus as StateFlow<String>
}

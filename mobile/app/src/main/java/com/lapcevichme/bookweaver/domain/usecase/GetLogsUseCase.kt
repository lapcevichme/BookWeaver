package com.lapcevichme.bookweaver.domain.usecase

import com.lapcevichme.network.ServerRepository
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

class GetLogsUseCase @Inject constructor(
    private val serverRepository: ServerRepository
) {
    /**
     * Предоставляет поток списка логов.
     */
    operator fun invoke(): StateFlow<List<String>> {
        return serverRepository.logs
    }
}

package com.lapcevichme.bookweaver.domain.usecase.connection

import com.lapcevichme.bookweaver.domain.repository.ConnectionRepository
import javax.inject.Inject

class StartAutoReconnectUseCase @Inject constructor(private val connectionRepository: ConnectionRepository) {
    operator fun invoke() {
        connectionRepository.start()
    }
}

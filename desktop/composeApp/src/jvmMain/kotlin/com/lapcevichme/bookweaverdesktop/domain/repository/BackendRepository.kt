package com.lapcevichme.bookweaverdesktop.domain.repository

import com.lapcevichme.bookweaverdesktop.domain.model.BackendServerStatus
import kotlinx.coroutines.flow.StateFlow

/**
 * Контракт для управления жизненным циклом Python-бэкенда.
 */
interface BackendRepository {
    val backendState: StateFlow<BackendServerStatus>
    val logs: StateFlow<List<String>>

    suspend fun start()
    suspend fun stop()
}

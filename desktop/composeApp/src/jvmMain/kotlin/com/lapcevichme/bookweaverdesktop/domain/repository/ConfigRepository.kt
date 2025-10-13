package com.lapcevichme.bookweaverdesktop.domain.repository

/**
 * Контракт для работы с конфигурацией бэкенда.
 */
interface ConfigRepository {
    suspend fun getConfigContent(): Result<String>
    suspend fun saveConfigContent(content: String): Result<Unit>
}


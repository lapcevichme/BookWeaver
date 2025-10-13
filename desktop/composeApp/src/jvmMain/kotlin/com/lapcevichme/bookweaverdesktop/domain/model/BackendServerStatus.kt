package com.lapcevichme.bookweaverdesktop.domain.model

/**
 * Доменная модель, описывающая текущий статус Python бэкенда.
 */
data class BackendServerStatus(
    val state: BackendServerState,
    val message: String? = null
)

/**
 * Перечисление возможных состояний Python бэкенда.
 */
enum class BackendServerState {
    INITIALIZING,
    READY,
    ERROR
}

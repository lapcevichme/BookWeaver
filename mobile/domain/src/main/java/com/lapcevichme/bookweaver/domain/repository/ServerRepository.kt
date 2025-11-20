package com.lapcevichme.bookweaver.domain.repository

import com.lapcevichme.bookweaver.domain.model.ServerConnection
import kotlinx.coroutines.flow.Flow

/**
 * Репозиторий для управления информацией о подключении к серверу BookWeaver.
 * Предоставляет методы для сохранения, получения и удаления данных о соединении.
 */
interface ServerRepository {

    /**
     * Возвращает [Flow], который эмитит текущие данные о подключении к серверу.
     * Эмиттит `null`, если данные о подключении отсутствуют.
     *
     * @return [Flow] с [ServerConnection] или `null`.
     */
    fun getServerConnection(): Flow<ServerConnection?>

    /**
     * Асинхронно сохраняет данные для подключения к серверу.
     *
     * @param host Полный адрес хоста, включая протокол и порт (например, "http://192.168.1.10:8080").
     * @param token Токен для авторизации на сервере.
     */
    suspend fun saveServerConnection(host: String, token: String)

    /**
     * Асинхронно удаляет сохраненные данные о подключении к серверу.
     */
    suspend fun clearServerConnection()

    /**
     * Синхронно возвращает текущие данные о подключении.
     * Этот метод не является `suspend`-функцией для использования в местах,
     * где асинхронные вызовы невозможны, например, в сетевых Interceptor'ах.
     * Реализация этого метода в слое `data` должна кэшировать значение для быстрого доступа.
     *
     * @return [ServerConnection], если данные сохранены, иначе `null`.
     */
    fun getCurrentConnection(): ServerConnection?
}

package com.lapcevichme.bookweaver.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * Интерфейс репозитория для хранения настроек пользователя.
 */
interface UserPreferencesRepository {

    /**
     * Получить Flow с текущей скоростью воспроизведения.
     */
    fun getPlaybackSpeed(): Flow<Float>

    /**
     * Сохранить новую скорость воспроизведения.
     */
    suspend fun savePlaybackSpeed(speed: Float)

    /**
     * Получить Flow с текущей громкостью эмбиента.
     */
    fun getAmbientVolume(): Flow<Float>

    /**
     * Сохранить новую громкость эмбиента.
     */
    suspend fun saveAmbientVolume(volume: Float)

    /**
     * Получить Flow с ключом текущей темы (например, "light", "dark").
     * Возвращает null, если не установлено.
     */
    fun getThemeSetting(): Flow<String?>

    /**
     * Сохранить ключ новой темы.
     */
    suspend fun saveThemeSetting(themeKey: String)
}

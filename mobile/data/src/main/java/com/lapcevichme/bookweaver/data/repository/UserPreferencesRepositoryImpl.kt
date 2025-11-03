package com.lapcevichme.bookweaver.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import com.lapcevichme.bookweaver.data.dataStore
import com.lapcevichme.bookweaver.domain.repository.UserPreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserPreferencesRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : UserPreferencesRepository {

    private val dataStore = context.dataStore

    // Ключи для хранения настроек
    private object PreferencesKeys {
        val PLAYBACK_SPEED = floatPreferencesKey("playback_speed")
        val AMBIENT_VOLUME = floatPreferencesKey("ambient_volume")
    }

    // --- Playback Speed ---

    override fun getPlaybackSpeed(): Flow<Float> {
        return dataStore.data.map { preferences ->
            preferences[PreferencesKeys.PLAYBACK_SPEED] ?: 1.0f
        }
    }

    override suspend fun savePlaybackSpeed(speed: Float) {
        dataStore.edit { settings ->
            settings[PreferencesKeys.PLAYBACK_SPEED] = speed
        }
    }

    // --- Ambient Volume ---

    override fun getAmbientVolume(): Flow<Float> {
        return dataStore.data.map { preferences ->
            preferences[PreferencesKeys.AMBIENT_VOLUME] ?: 0.5f
        }
    }

    override suspend fun saveAmbientVolume(volume: Float) {
        dataStore.edit { settings ->
            settings[PreferencesKeys.AMBIENT_VOLUME] = volume
        }
    }
}

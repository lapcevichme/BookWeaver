package com.lapcevichme.bookweaver.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lapcevichme.bookweaver.data.dataStore
import com.lapcevichme.bookweaver.domain.model.ServerConnection
import com.lapcevichme.bookweaver.domain.repository.ServerRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : ServerRepository {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private object PreferencesKeys {
        val SERVER_HOST = stringPreferencesKey("server_host")
        val SERVER_TOKEN = stringPreferencesKey("server_token")
    }

    private val _connectionCache = MutableStateFlow<ServerConnection?>(null)

    init {
        scope.launch {
            getServerConnection().collect { connection ->
                _connectionCache.value = connection
            }
        }
    }

    override fun getServerConnection(): Flow<ServerConnection?> {
        return context.dataStore.data.map { preferences ->
            val host = preferences[PreferencesKeys.SERVER_HOST]
            val token = preferences[PreferencesKeys.SERVER_TOKEN]

            if (host != null && token != null) ServerConnection(host, token) else null
        }
    }

    override suspend fun saveServerConnection(host: String, token: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SERVER_HOST] = host
            preferences[PreferencesKeys.SERVER_TOKEN] = token
        }
    }

    override suspend fun clearServerConnection() {
        context.dataStore.edit { preferences ->
            preferences.remove(PreferencesKeys.SERVER_HOST)
            preferences.remove(PreferencesKeys.SERVER_TOKEN)
        }
    }

    override fun getCurrentConnection(): ServerConnection? {
        return _connectionCache.value
    }
}
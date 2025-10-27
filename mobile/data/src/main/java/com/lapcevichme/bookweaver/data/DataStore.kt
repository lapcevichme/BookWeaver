package com.lapcevichme.bookweaver.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * Централизованное определение DataStore.
 * Теперь к нему могут обращаться несколько репозиториев.
 */
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")


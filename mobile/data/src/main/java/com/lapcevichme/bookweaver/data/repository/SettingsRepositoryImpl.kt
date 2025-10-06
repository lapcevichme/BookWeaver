package com.lapcevichme.bookweaver.data.repository

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import com.lapcevichme.bookweaver.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    // Инжектируем Application, чтобы получить доступ к Context
    application: Application
) : SettingsRepository {

    private val prefs = application.getSharedPreferences("server_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val FINGERPRINT_KEY = "server_fingerprint"
    }

    override fun saveServerFingerprint(fingerprint: String) {
        // Используем удобный KTX extension-метод
        prefs.edit {
            putString(FINGERPRINT_KEY, fingerprint)
        }
    }

    override fun getServerFingerprint(): String? {
        return prefs.getString(FINGERPRINT_KEY, null)
    }
}
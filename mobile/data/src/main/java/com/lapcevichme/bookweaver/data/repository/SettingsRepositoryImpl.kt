package com.lapcevichme.bookweaver.data.repository

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.lapcevichme.bookweaver.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    // Инжектируем Application, чтобы получить доступ к Context
    application: Application
) : SettingsRepository {
    private val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        "server_prefs_encrypted", // Имя файла будет другим
        masterKeyAlias,
        application,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

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
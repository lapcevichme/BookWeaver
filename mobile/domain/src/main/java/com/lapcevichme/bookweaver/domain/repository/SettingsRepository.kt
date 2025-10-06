package com.lapcevichme.bookweaver.domain.repository

interface SettingsRepository {
    fun saveServerFingerprint(fingerprint: String)
    fun getServerFingerprint(): String?
}

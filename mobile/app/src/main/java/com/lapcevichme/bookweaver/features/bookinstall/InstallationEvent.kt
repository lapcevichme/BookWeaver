package com.lapcevichme.bookweaver.features.bookinstall

import android.net.Uri

sealed class InstallationEvent {
    data class UrlChanged(val url: String) : InstallationEvent()
    object InstallFromUrlClicked : InstallationEvent()
    data class InstallFromFile(val uri: Uri?) : InstallationEvent()
    object ResultHandled : InstallationEvent()
}
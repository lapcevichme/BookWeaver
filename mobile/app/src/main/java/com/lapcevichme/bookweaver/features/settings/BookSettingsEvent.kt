package com.lapcevichme.bookweaver.features.settings

sealed class BookSettingsEvent {
    object DeleteClicked : BookSettingsEvent()
    object DeleteConfirmed : BookSettingsEvent()
    object DeleteCancelled : BookSettingsEvent()
    object DeletionResultHandled : BookSettingsEvent()
}

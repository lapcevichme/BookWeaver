package com.lapcevichme.bookweaver.features.settings.book

sealed class BookSettingsEvent {
    object DeleteClicked : BookSettingsEvent()
    object DeleteConfirmed : BookSettingsEvent()
    object DeleteCancelled : BookSettingsEvent()
    object DeletionResultHandled : BookSettingsEvent()
}

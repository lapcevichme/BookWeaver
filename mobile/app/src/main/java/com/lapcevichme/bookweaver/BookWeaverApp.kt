package com.lapcevichme.bookweaver

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class BookWeaverApp : Application() {

    @Inject
    lateinit var connectionManager: ConnectionManager

    override fun onCreate() {
        super.onCreate()
        // Сразу после super.onCreate() наш connectionManager будет создан
        // и начнет слушать статус соединения в фоне.
    }
}
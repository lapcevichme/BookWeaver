package com.lapcevichme.bookweaver

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class BookWeaverApp : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}
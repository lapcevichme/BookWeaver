package com.lapcevichme.bookweaver

import android.app.Application
import com.lapcevichme.bookweaver.domain.repository.ConnectionRepository
import com.lapcevichme.bookweaver.core.service.ServiceController
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class BookWeaverApp : Application() {

    // Инжектируем контроллер, чтобы запустить его логику в init {}
    @Inject
    lateinit var serviceController: ServiceController

    // Инжектируем репозиторий для авто-переподключения
    @Inject
    lateinit var connectionRepository: ConnectionRepository

    override fun onCreate() {
        super.onCreate()
        // Запускаем логику авто-переподключения
        connectionRepository.start()

        // serviceController ничего вызывать не нужно,
        // Hilt его создаст, и его init-блок выполнится сам.
    }
}
package com.lapcevichme.bookweaver

import android.app.Application
import com.lapcevichme.bookweaver.domain.repository.ConnectionRepository
import com.lapcevichme.bookweaver.presentation.service.ServiceController
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class BookWeaverApp : Application() {

    // 1. Инжектируем контроллер, чтобы запустить его логику в init {}
    @Inject
    lateinit var serviceController: ServiceController

    // 2. Инжектируем репозиторий для авто-переподключения
    @Inject
    lateinit var connectionRepository: ConnectionRepository

    override fun onCreate() {
        super.onCreate()
        // 3. Запускаем логику авто-переподключения
        connectionRepository.start()

        // 4. serviceController ничего вызывать не нужно,
        //    Hilt его создаст, и его init-блок выполнится сам.
    }
}
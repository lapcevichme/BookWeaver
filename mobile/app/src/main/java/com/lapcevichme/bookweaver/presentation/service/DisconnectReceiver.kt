package com.lapcevichme.bookweaver.presentation.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lapcevichme.bookweaver.data.ServerRepositoryImpl
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class DisconnectReceiver : BroadcastReceiver() {

    @Inject
    lateinit var serverRepositoryImpl: ServerRepositoryImpl

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == ConnectionService.ACTION_DISCONNECT) {
            val pendingResult = goAsync()
            scope.launch {
                try {
                    serverRepositoryImpl.disconnect()
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}


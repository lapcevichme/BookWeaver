package com.lapcevichme.bookweaver.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.lapcevichme.bookweaver.R
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ConnectionService : Service() {
    private lateinit var notificationManager: NotificationManager

    companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "ConnectionServiceChannel"
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_UPDATE_STATUS = "ACTION_UPDATE_STATUS"
        const val EXTRA_STATUS = "EXTRA_STATUS"
        const val ACTION_DISCONNECT = "ACTION_DISCONNECT"
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val status = intent.getStringExtra(EXTRA_STATUS) ?: "Connecting..."
                startForeground(NOTIFICATION_ID, createNotification(status))
            }

            ACTION_UPDATE_STATUS -> {
                val status = intent.getStringExtra(EXTRA_STATUS) ?: "Updating..."
                notificationManager.notify(NOTIFICATION_ID, createNotification(status))
            }

            ACTION_STOP -> {
                // Используем stopForeground с флагом REMOVE, чтобы убрать уведомление
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        // Говорим системе, что сервис "липкий". Если его убьют, его нужно перезапустить.
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Connection Status",
                NotificationManager.IMPORTANCE_LOW // LOW, чтобы не было звука при появлении
            ).apply {
                description = "Shows the current server connection status"
            }
            notificationManager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(status: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Bookweaver Connection")
        .setContentText(status)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .addAction(createDisconnectAction())
        .setOngoing(true) // Уведомление нельзя смахнуть
        .setOnlyAlertOnce(true) // Не будет издавать звук при обновлении
        .build()

    private fun createDisconnectAction(): NotificationCompat.Action {
        val disconnectIntent = Intent(this, DisconnectReceiver::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val disconnectPendingIntent = PendingIntent.getBroadcast(this, 0, disconnectIntent, flags)
        return NotificationCompat.Action(0, "Disconnect", disconnectPendingIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}


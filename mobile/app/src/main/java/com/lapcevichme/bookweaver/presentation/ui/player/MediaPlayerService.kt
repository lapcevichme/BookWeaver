package com.lapcevichme.bookweaver.presentation.ui.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.core.text.buildSpannedString
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class PlayerState(
    val isPlaying: Boolean = false,
    val currentPosition: Long = 0L,
    val duration: Long = 0L,
    val fileName: String = "",
    val albumArt: Bitmap? = null,
    val playbackSpeed: Float = 1.0f,
    val currentSubtitle: CharSequence = "",
    val subtitlesEnabled: Boolean = true
)

class MediaPlayerService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSessionCompat
    private var placeholderBitmap: Bitmap? = null
    private var currentMediaUri: Uri? = null


    private val _playerStateFlow = MutableStateFlow(PlayerState())
    val playerStateFlow = _playerStateFlow.asStateFlow()

    companion object {
        private const val TAG = "AudioPlayerServiceLog"
        const val CHANNEL_ID = "AudioPlayerChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_PLAY = "com.lapcevichme.bookweaver.PLAY"
        const val ACTION_PAUSE = "com.lapcevichme.bookweaver.PAUSE"
        const val ACTION_NEXT = "com.lapcevichme.bookweaver.NEXT"
        const val ACTION_PREVIOUS = "com.lapcevichme.bookweaver.PREVIOUS"
        const val ACTION_STOP = "com.lapcevichme.bookweaver.STOP"
    }

    inner class LocalBinder : Binder() {
        fun getService(): MediaPlayerService = this@MediaPlayerService
    }

    override fun onBind(intent: Intent): IBinder = binder

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        try {
            // TODO: R.drawable.album_art_placeholder
            // placeholderBitmap = BitmapFactory.decodeResource(resources, R.drawable.album_art_placeholder)
        } catch (e: Exception) {
            // Игнорируем, если ресурса нет
        }

        player = ExoPlayer.Builder(this).build()
        mediaSession = MediaSessionCompat(this, "AudioPlayerSession")
        mediaSession.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                super.onPlay(); play()
            }

            override fun onPause() {
                super.onPause(); pause()
            }

            override fun onSeekTo(pos: Long) {
                super.onSeekTo(pos); seekTo(pos)
            }

            override fun onStop() {
                super.onStop(); stopSelf()
            }
        })
        mediaSession.isActive = true

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayerState()
                updateNotification()
                if (isPlaying) startPositionUpdates() else stopPositionUpdates()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    player.seekTo(0)
                    player.playWhenReady = false
                }
                updatePlayerState()
                updateNotification()
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                updatePlayerState()
            }

            override fun onCues(cues: List<Cue>) {
                val subtitleText = cues.firstOrNull()?.text ?: buildSpannedString { }
                Log.d(TAG, "onCues triggered. Text: '$subtitleText'")
                _playerStateFlow.value = _playerStateFlow.value.copy(currentSubtitle = subtitleText)
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateNotification()
        when (intent?.action) {
            ACTION_PLAY -> play()
            ACTION_PAUSE -> pause()
            ACTION_PREVIOUS -> seekTo((player.currentPosition - 10000).coerceAtLeast(0L))
            ACTION_NEXT -> seekTo((player.currentPosition + 10000).coerceAtMost(player.duration))
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun findSubtitleFile(audioUri: Uri): Uri? {
        val audioPath = audioUri.path ?: run {
            Log.w(TAG, "Audio URI path is null. Cannot search for subtitles.")
            return null
        }
        Log.d(TAG, "Searching for subtitles for audio file: $audioPath")
        val audioFile = File(audioPath)
        val srtPath = audioFile.absolutePath.replaceAfterLast('.', "srt")
        Log.d(TAG, "Expected subtitle file path: $srtPath")
        val srtFile = File(srtPath)

        return if (srtFile.exists() && srtFile.canRead()) {
            Log.i(TAG, "Subtitle file FOUND at: $srtPath")
            srtFile.toUri()
        } else {
            Log.w(TAG, "Subtitle file NOT found or cannot be read at the expected path.")
            null
        }
    }

    fun playFile(uri: Uri, context: Context) {
        // --- НОВОЕ: Сохраняем URI при запуске воспроизведения ---
        currentMediaUri = uri

        Log.d(TAG, "playFile called with URI: $uri")
        player.stop()
        player.clearMediaItems()

        val mediaItemBuilder = MediaItem.Builder().setUri(uri)

        val subtitleUri = findSubtitleFile(uri)
        if (subtitleUri != null) {
            Log.i(TAG, "Found subtitle URI: $subtitleUri. Adding to MediaItem.")
            val subtitleConfiguration = MediaItem.SubtitleConfiguration.Builder(subtitleUri)
                .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                .setLanguage("ru")
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
            mediaItemBuilder.setSubtitleConfigurations(listOf(subtitleConfiguration))
        } else {
            Log.w(TAG, "No subtitle URI found. Playing audio without subtitles.")
        }

        player.setMediaItem(mediaItemBuilder.build())
        player.prepare()
        play()
        setPlaybackSpeed(1.0f)
        toggleSubtitles(subtitleUri != null)

        val fileName = File(uri.path!!).nameWithoutExtension
        val albumArt = getAlbumArt(uri, context)

        _playerStateFlow.value = PlayerState(
            fileName = fileName,
            albumArt = albumArt,
            subtitlesEnabled = subtitleUri != null
        )
    }

    // Метод для перезагрузки плеера с новыми субтитрами
    fun reloadWithSubtitles() {
        val uri = currentMediaUri ?: run {
            Log.e(TAG, "reloadWithSubtitles called but currentMediaUri is null.")
            return
        }
        Log.d(TAG, "Reloading player for URI: $uri to check for new subtitles.")

        val currentPosition = player.currentPosition
        val wasPlaying = player.isPlaying

        // Просто вызываем playFile снова. Он перестроит MediaItem и найдет новый SRT файл.
        playFile(uri, applicationContext)

        // Восстанавливаем состояние
        player.seekTo(currentPosition)
        if (wasPlaying) {
            player.play()
        } else {
            // playFile по умолчанию запускает воспроизведение, так что ставим на паузу, если не играло
            player.pause()
        }
        Log.d(
            TAG,
            "Player reloaded. Position restored to $currentPosition. Was playing: $wasPlaying"
        )
    }

    fun play() {
        player.play()
    }

    fun pause() {
        player.pause()
    }

    fun seekTo(position: Long) {
        player.seekTo(position); updatePlayerState()
    }

    fun setPlaybackSpeed(speed: Float) {
        player.playbackParameters = PlaybackParameters(speed)
    }

    fun toggleSubtitles(enabled: Boolean) {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
            .build()
        _playerStateFlow.value = _playerStateFlow.value.copy(subtitlesEnabled = enabled)
    }


    private fun getAlbumArt(uri: Uri, context: Context): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val art = retriever.embeddedPicture
            if (art != null) {
                BitmapFactory.decodeByteArray(art, 0, art.size)
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            retriever.release()
        }
    }

    private fun updatePlayerState() {
        if (player.playbackState == Player.STATE_IDLE) return
        _playerStateFlow.value = _playerStateFlow.value.copy(
            isPlaying = player.isPlaying,
            duration = if (player.duration > 0) player.duration else 0L,
            currentPosition = player.currentPosition,
            playbackSpeed = player.playbackParameters.speed
        )
    }

    private var positionUpdateJob: Job? = null
    private fun startPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = serviceScope.launch {
            while (isActive) {
                updatePlayerState()
                delay(1000)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
    }

    private fun updateNotification() {
        val isPlaying = player.isPlaying
        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause,
                "Pause",
                createPendingIntent(ACTION_PAUSE)
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play,
                "Play",
                createPendingIntent(ACTION_PLAY)
            )
        }

        val prevAction = NotificationCompat.Action(
            android.R.drawable.ic_media_previous,
            "Previous",
            createPendingIntent(ACTION_PREVIOUS)
        )
        val nextAction = NotificationCompat.Action(
            android.R.drawable.ic_media_next,
            "Next",
            createPendingIntent(ACTION_NEXT)
        )

        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, _playerStateFlow.value.fileName)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Неизвестный исполнитель")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, player.duration)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, _playerStateFlow.value.albumArt)
            .build()
        mediaSession.setMetadata(metadata)

        val playbackState = PlaybackStateCompat.Builder()
            .setState(
                if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                player.currentPosition,
                1.0f
            )
            .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SEEK_TO or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_STOP)
            .build()
        mediaSession.setPlaybackState(playbackState)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(_playerStateFlow.value.fileName.ifEmpty { "Аудиоплеер" })
            .setContentText("Неизвестный исполнитель")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setLargeIcon(_playerStateFlow.value.albumArt ?: placeholderBitmap)
            .setContentIntent(mediaSession.controller.sessionActivity)
            .setDeleteIntent(createPendingIntent(ACTION_STOP))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(prevAction)
            .addAction(playPauseAction)
            .addAction(nextAction)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setOngoing(isPlaying)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, MediaPlayerService::class.java).setAction(action)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getService(this, 0, intent, flags)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Воспроизведение аудио", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        player.release()
        mediaSession.release()
    }
}

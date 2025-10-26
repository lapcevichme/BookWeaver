package com.lapcevichme.bookweaver.core.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Binder
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.text.SpannableString
import android.text.style.StyleSpan
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.lapcevichme.bookweaver.core.service.parsing.SubtitleEntry
import com.lapcevichme.bookweaver.domain.model.ChapterMedia
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
import kotlinx.serialization.json.Json
import java.io.File

class MediaPlayerService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSessionCompat
    private var placeholderBitmap: Bitmap? = null

    private var currentSubtitlesPath: String? = null
    private var currentSubtitles: List<SubtitleEntry> = emptyList()
    private val json = Json { ignoreUnknownKeys = true }

    private var currentMediaItemIndex = 0
    private var basePositionOffsetMs = 0L
    private var totalChapterDurationMs = 0L

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
                    player.seekTo(0, 0L)
                    player.playWhenReady = false
                }
                updatePlayerState()
                updateNotification()
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                updatePlayerState()
            }

            override fun onCues(cues: List<Cue>) {
                // не используется
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentMediaItemIndex = player.currentMediaItemIndex
                basePositionOffsetMs =
                    currentSubtitles.getOrNull(currentMediaItemIndex)?.startMs ?: 0L
                Log.d(
                    TAG,
                    "MediaItem transition. New index: $currentMediaItemIndex, new offset: $basePositionOffsetMs"
                )
                updatePlayerState()
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateNotification()
        when (intent?.action) {
            ACTION_PLAY -> play()
            ACTION_PAUSE -> pause()
            ACTION_PREVIOUS -> player.seekToPreviousMediaItem()
            ACTION_NEXT -> player.seekToNextMediaItem()
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    @OptIn(UnstableApi::class)
    fun setMedia(media: ChapterMedia, chapterTitle: String, coverPath: String?) {
        val subtitlesPath = media.subtitlesPath
        val audioDirectoryPath = media.audioDirectoryPath

        if (subtitlesPath == null) {
            Log.e(TAG, "Error setting media: subtitlesPath or audioDirectoryPath is null")
            _playerStateFlow.value = _playerStateFlow.value.copy(error = "Media paths are missing")
            return
        }

        if (subtitlesPath == currentSubtitlesPath) {
            Log.d(TAG, "Media is already set. Skipping.")
            return
        }

        Log.d(TAG, "setMedia called. Subtitles: $subtitlesPath, AudioDir: $audioDirectoryPath")
        currentSubtitlesPath = subtitlesPath

        player.stop()
        player.clearMediaItems()
        currentSubtitles = emptyList()

        currentMediaItemIndex = 0
        basePositionOffsetMs = 0L
        totalChapterDurationMs = 0L

        try {
            val subtitlesFile = File(subtitlesPath)
            val subtitlesJson = subtitlesFile.readText()
            currentSubtitles = json.decodeFromString<List<SubtitleEntry>>(subtitlesJson)

            if (currentSubtitles.isEmpty()) {
                throw Exception("Файл субтитров пуст")
            }

            totalChapterDurationMs = currentSubtitles.lastOrNull()?.endMs ?: 0L

            val mediaItems = mutableListOf<MediaItem>()

            for (entry in currentSubtitles) {
                val audioFile = File(audioDirectoryPath, entry.audioFile)
                if (!audioFile.exists()) {
                    Log.w(TAG, "Missing audio file: ${entry.audioFile}")
                    continue
                }

                val mediaItem = MediaItem.fromUri(audioFile.toUri())
                mediaItems.add(mediaItem)
            }

            player.setMediaItems(mediaItems)
            player.prepare()
            play()
            setPlaybackSpeed(1.0f)

            var loadedBitmap: Bitmap? = null
            if (coverPath != null) {
                try {
                    loadedBitmap = BitmapFactory.decodeFile(coverPath)
                    placeholderBitmap = loadedBitmap // Сохраняем для уведомлений
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load cover image", e)
                }
            }

            _playerStateFlow.value = PlayerState(
                fileName = chapterTitle,
                albumArt = loadedBitmap,
                subtitlesEnabled = true,
                duration = totalChapterDurationMs
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error setting media source", e)
        }
    }

    fun play() {
        player.play()
    }

    fun pause() {
        player.pause()
    }

    fun togglePlayPause() {
        if (player.isPlaying) {
            pause()
        } else {
            play()
        }
    }

    fun seekTo(position: Long) {
        if (currentSubtitles.isEmpty()) return

        val targetItemIndex = currentSubtitles.indexOfLast { position >= it.startMs }
            .coerceAtLeast(0)
        val targetItem = currentSubtitles.getOrNull(targetItemIndex) ?: currentSubtitles.first()
        val relativePosition = (position - targetItem.startMs).coerceAtLeast(0L)
        player.seekTo(targetItemIndex, relativePosition)
        updatePlayerState()
    }

    fun setPlaybackSpeed(speed: Float) {
        player.playbackParameters = PlaybackParameters(speed)
    }

    // вкл/выкл субтитров
    fun toggleSubtitles(enabled: Boolean) {
        _playerStateFlow.value = _playerStateFlow.value.copy(subtitlesEnabled = enabled)
        // Немедленно обновляем, чтобы скрыть/показать субтитры
        updatePlayerState()
    }

    private fun updatePlayerState() {
        if (player.playbackState == Player.STATE_IDLE || currentSubtitles.isEmpty()) return

        val absolutePosition = basePositionOffsetMs + player.currentPosition

        // Логика "Караоке" субтитров
        val currentSubtitleText: CharSequence =
            // Проверяем, включены ли субтитры
            if (_playerStateFlow.value.subtitlesEnabled) {
                // Находим текущую реплику
                val currentEntry = currentSubtitles.firstOrNull {
                    absolutePosition >= it.startMs && absolutePosition < it.endMs
                }

                if (currentEntry == null) {
                    "" // Пустая строка, если сейчас тишина
                } else if (currentEntry.words.isEmpty()) {
                    currentEntry.text // Показываем весь текст, если нет таймингов слов
                } else {
                    // Собираем SpannableString с подсветкой
                    buildKaraokeSubtitle(currentEntry, absolutePosition)
                }
            } else {
                "" // Пустая строка, если субтитры выключены
            }

        _playerStateFlow.value = _playerStateFlow.value.copy(
            isPlaying = player.isPlaying,
            duration = totalChapterDurationMs,
            currentPosition = absolutePosition,
            playbackSpeed = player.playbackParameters.speed,
            currentSubtitle = currentSubtitleText
        )
    }

    // Хелпер для создания "Караоке" строки
    private fun buildKaraokeSubtitle(entry: SubtitleEntry, absolutePosition: Long): CharSequence {
        try {
            val spannable = SpannableString(entry.text)
            var charIndex = 0

            for (word in entry.words) {
                val startChar = charIndex
                val endChar = (startChar + word.word.length).coerceAtMost(spannable.length)

                // Проверяем, что endChar не выходит за пределы (на случай ошибок в JSON)
                if (startChar >= endChar) continue

                // Если текущее слово активно, подсвечиваем его
                if (absolutePosition >= word.start && absolutePosition <= word.end) {
                    spannable.setSpan(
                        StyleSpan(Typeface.BOLD),
                        startChar,
                        endChar,
                        SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    // Todo: подумать про цвет
                    // spannable.setSpan(
                    //    ForegroundColorSpan(Color.YELLOW),
                    //    startChar,
                    //    endChar,
                    //    SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
                    // )
                }

                charIndex = endChar
            }
            return spannable
        } catch (e: Exception) {
            Log.e(TAG, "Error building karaoke subtitle: ${e.message}")
            return entry.text
        }
    }


    private var positionUpdateJob: Job? = null
    private fun startPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = serviceScope.launch {
            while (isActive) {
                updatePlayerState()
                delay(100)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
    }

    @OptIn(UnstableApi::class)
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
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "BookWeaver")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, totalChapterDurationMs)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, placeholderBitmap)
            .build()
        mediaSession.setMetadata(metadata)

        val playbackState = PlaybackStateCompat.Builder()
            .setState(
                if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                basePositionOffsetMs + player.currentPosition,
                1.0f
            )
            .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SEEK_TO or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_STOP)
            .build()
        mediaSession.setPlaybackState(playbackState)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(_playerStateFlow.value.fileName.ifEmpty { "Аудиоплеер" })
            .setContentText("BookWeaver")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setLargeIcon(placeholderBitmap)
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
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getService(this, 0, intent, flags)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Воспроизведение аудио", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        player.release()
        mediaSession.release()
    }
}

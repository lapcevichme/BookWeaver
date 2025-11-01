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
import com.lapcevichme.bookweaver.domain.usecase.player.SaveListenProgressUseCase
import dagger.hilt.android.AndroidEntryPoint
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
import javax.inject.Inject

@AndroidEntryPoint
class MediaPlayerService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // --- 1. Внедряем UseCase для сохранения ---
    @Inject
    lateinit var saveListenProgressUseCase: SaveListenProgressUseCase
    // ---

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSessionCompat
    private var placeholderBitmap: Bitmap? = null

    // --- 2. Добавляем ID для сохранения ---
    private var currentBookId: String? = null
    private var currentChapterId: String? = null
    // ---

    private var currentSubtitlesPath: String? = null
    private var currentSubtitles: List<SubtitleEntry> = emptyList()
    private val json = Json { ignoreUnknownKeys = true }

    private var currentMediaItemIndex = 0
    private var basePositionOffsetMs = 0L
    private var totalChapterDurationMs = 0L

    private val _playerStateFlow = MutableStateFlow(PlayerState())
    val playerStateFlow = _playerStateFlow.asStateFlow()

    private var saveProgressJob: Job? = null
    private var lastSaveTimeMs: Long = 0
    private val SAVE_THROTTLE_MS = 10_000L // 10 секунд
    private val SAVE_DEBOUNCE_MS = 1_000L // 1 секунда

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
                if (isPlaying) {
                    startPositionUpdates()
                } else {
                    stopPositionUpdates()
                    // Принудительное сохранение на ПАУЗУ
                    Log.d(TAG, "Player is PAUSED. Forcing debounced save.")
                    triggerSave(
                        position = _playerStateFlow.value.currentPosition,
                        isDebounce = true, // Сохраняем с задержкой (на случай, если это seek)
                        isFinalSave = false
                    )
                }
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
            ACTION_STOP -> {
                // --- 4. Обновляем ACTION_STOP ---
                // Принудительно сохраняем, прежде чем убить сервис
                Log.d(TAG, "ACTION_STOP received. Forcing final save and stopping.")
                triggerSave(
                    position = _playerStateFlow.value.currentPosition,
                    isDebounce = false,
                    isFinalSave = true
                )
                stopSelf() // Останавливаем сервис
            }
        }
        return START_NOT_STICKY
    }

    // --- 5. Перехватываем "убийство" приложения свайпом ---
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "onTaskRemoved: App swiped away. Forcing final save.")
        // Принудительное, немедленное сохранение
        triggerSave(
            position = _playerStateFlow.value.currentPosition,
            isDebounce = false,
            isFinalSave = true // Флаг немедленного сохранения
        )
    }


    @OptIn(UnstableApi::class)
    fun setMedia(
        bookId: String,
        chapterId: String,
        media: ChapterMedia,
        chapterTitle: String,
        coverPath: String?,
        playWhenReady: Boolean,
        seekToPositionMs: Long? = null
    ) {
        val subtitlesPath = media.subtitlesPath
        val audioDirectoryPath = media.audioDirectoryPath

        if (subtitlesPath == null) {
            Log.e(TAG, "Error setting media: subtitlesPath or audioDirectoryPath is null")
            _playerStateFlow.value = _playerStateFlow.value.copy(error = "Media paths are missing")
            return
        }

        // --- 6. Сохраняем ID ---
        Log.d(
            TAG,
            "setMedia called for $bookId / $chapterId. Play: $playWhenReady, Seek: $seekToPositionMs"
        )
        // Принудительно сохраняем прогресс СТАРОЙ главы, прежде чем загрузить новую
        if (currentChapterId != null && _playerStateFlow.value.currentPosition > 0) {
            Log.d(TAG, "setMedia: Saving progress for old chapter $currentChapterId")
            triggerSave(
                position = _playerStateFlow.value.currentPosition,
                isDebounce = false,
                isFinalSave = true
            )
        }

        currentBookId = bookId
        currentChapterId = chapterId
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

            if (seekToPositionMs != null) {
                Log.d(TAG, "setMedia: Перемотка (до prepare) на $seekToPositionMs")
                seekTo(seekToPositionMs)
            }

            player.playWhenReady = playWhenReady

            player.prepare()

            setPlaybackSpeed(1.0f)

            var loadedBitmap: Bitmap? = null
            if (coverPath != null) {
                try {
                    loadedBitmap = BitmapFactory.decodeFile(coverPath)
                    placeholderBitmap = loadedBitmap
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load cover image", e)
                }
            }

            _playerStateFlow.value = PlayerState(
                fileName = chapterTitle,
                albumArt = loadedBitmap,
                subtitlesEnabled = true,
                duration = totalChapterDurationMs,
                currentPosition = seekToPositionMs ?: 0L,
                loadedChapterId = currentSubtitlesPath ?: ""
            )

            updatePlayerState()
            updateNotification()

        } catch (e: Exception) {
            Log.e(TAG, "Error setting media source", e)
            currentBookId = null
            currentChapterId = null
            currentSubtitlesPath = null
            _playerStateFlow.value = _playerStateFlow.value.copy(
                error = "Ошибка установки медиа: ${e.message}",
                loadedChapterId = ""
            )
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

        val clampedPosition = position.coerceIn(0L, totalChapterDurationMs)

        val targetItemIndex = currentSubtitles.indexOfLast { clampedPosition >= it.startMs }
            .coerceAtLeast(0)

        val targetItem = currentSubtitles.getOrNull(targetItemIndex) ?: currentSubtitles.first()

        val relativePosition = (clampedPosition - targetItem.startMs).coerceAtLeast(0L)

        Log.d(
            TAG,
            "seekTo: pos=$clampedPosition, targetIndex=$targetItemIndex, relativePos=$relativePosition"
        )

        basePositionOffsetMs = targetItem.startMs
        currentMediaItemIndex = targetItemIndex

        player.seekTo(targetItemIndex, relativePosition)

        if (player.playbackState != Player.STATE_IDLE) {
            updatePlayerState()
            // Сохраняем на seek
            triggerSave(
                position = clampedPosition,
                isDebounce = true, // С задержкой
                isFinalSave = false
            )
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        player.playbackParameters = PlaybackParameters(speed)
    }

    fun toggleSubtitles(enabled: Boolean) {
        _playerStateFlow.value = _playerStateFlow.value.copy(subtitlesEnabled = enabled)
        updatePlayerState()
    }

    /**
     * Останавливает воспроизведение, очищает плейлист,
     * принудительно сохраняет прогресс и сбрасывает состояние сервиса.
     * Используется при смене книги.
     */
    fun stopAndClear() {
        Log.d(TAG, "stopAndClear: Stopping player, clearing media, and resetting state.")

        // 1. Финальное сохранение (использует старое состояние, поэтому делаем в первую очередь)
        triggerSave(
            position = _playerStateFlow.value.currentPosition,
            isDebounce = false,
            isFinalSave = true
        )

        // 2. НЕМЕДЛЕННО сбрасываем публичное состояние.
        // Это гарантирует, что любые коллбэки (onIsPlayingChanged, onPlaybackStateChanged)
        // увидят, что loadedChapterId пуст, и не вызовут startForeground() в updateNotification.
        _playerStateFlow.value = PlayerState()

        // 3. Остановка плеера
        player.pause() // Теперь onIsPlayingChanged(false) -> updateNotification() -> if("") -> false
        player.stop() // Теперь onPlaybackStateChanged -> updateNotification() -> if("") -> false
        player.clearMediaItems() // Очищаем

        // 4. Сброс внутреннего состояния
        currentBookId = null
        currentChapterId = null
        currentSubtitlesPath = null
        currentSubtitles = emptyList()
        totalChapterDurationMs = 0L
        basePositionOffsetMs = 0L
        currentMediaItemIndex = 0

        // 5. Убираем нотификацию и сервис из foreground
        stopForeground(STOP_FOREGROUND_REMOVE) // true
        Log.d(TAG, "stopAndClear: State has been cleared.")
    }
    // --- КОНЕЦ НОВОГО МЕТОДА ---

    private fun updatePlayerState() {
        if (player.playbackState == Player.STATE_IDLE || currentSubtitles.isEmpty()) return

        val currentItemOffset =
            currentSubtitles.getOrNull(player.currentMediaItemIndex)?.startMs ?: 0L
        basePositionOffsetMs = currentItemOffset

        val absolutePosition = basePositionOffsetMs + player.currentPosition

        val currentSubtitleText: CharSequence =
            if (_playerStateFlow.value.subtitlesEnabled) {
                val currentEntry = currentSubtitles.firstOrNull {
                    absolutePosition >= it.startMs && absolutePosition < it.endMs
                } ?: currentSubtitles.lastOrNull {
                    it == currentSubtitles.last() && absolutePosition >= it.startMs && absolutePosition <= it.endMs
                }


                if (currentEntry == null) {
                    ""
                } else if (currentEntry.words.isEmpty()) {
                    currentEntry.text
                } else {
                    buildKaraokeSubtitle(currentEntry, absolutePosition)
                }
            } else {
                ""
            }

        _playerStateFlow.value = _playerStateFlow.value.copy(
            isPlaying = player.isPlaying,
            duration = totalChapterDurationMs,
            currentPosition = absolutePosition.coerceAtMost(totalChapterDurationMs),
            playbackSpeed = player.playbackParameters.speed,
            currentSubtitle = currentSubtitleText,
            loadedChapterId = currentSubtitlesPath ?: ""
        )
    }

    private fun buildKaraokeSubtitle(entry: SubtitleEntry, absolutePosition: Long): CharSequence {
        try {
            val spannable = SpannableString(entry.text)
            var charIndex = 0

            for (word in entry.words) {
                val startChar = charIndex
                val endChar = (startChar + word.word.length).coerceAtMost(spannable.length)

                if (startChar >= endChar) continue

                if (absolutePosition >= word.start && absolutePosition <= word.end) {
                    spannable.setSpan(
                        StyleSpan(Typeface.BOLD),
                        startChar,
                        endChar,
                        SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
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
                // --- 7. Запускаем сохранение (throttle) ---
                triggerSave(
                    position = _playerStateFlow.value.currentPosition,
                    isDebounce = false, // Это throttle
                    isFinalSave = false
                )
                delay(100) // Обновление UI
            }
        }
    }

    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
    }

    // --- 8. Новая функция сохранения ---
    /**
     * Запускает сохранение прогресса с логикой throttle/debounce
     * @param position Текущая позиция
     * @param isDebounce true - если это "отложенное" сохранение (на паузу, seek),
     * false - если это "мгновенное" (throttle, раз в 10 сек)
     * @param isFinalSave true - если это *немедленное* сохранение (выход, смена главы).
     * Игнорирует все задержки.
     */
    private fun triggerSave(position: Long, isDebounce: Boolean, isFinalSave: Boolean) {
        val bookId = currentBookId
        val chapterId = currentChapterId
        if (bookId == null || chapterId == null || position == 0L) {
            return // Нечего сохранять
        }

        // Отменяем предыдущую *запланированную* (debounce) задачу
        saveProgressJob?.cancel()

        val currentTimeMs = System.currentTimeMillis()

        // --- 1. Немедленное сохранение (высший приоритет) ---
        if (isFinalSave) {
            Log.d(TAG, "SaveProgress (Final): $position")
            // Запускаем реальное сохранение
            serviceScope.launch {
                try {
                    saveListenProgressUseCase(bookId, chapterId, position)
                    lastSaveTimeMs = System.currentTimeMillis() // Обновляем время
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            return
        }

        // --- 2. Throttle (раз в 10 сек) ---
        if (!isDebounce && currentTimeMs - lastSaveTimeMs > SAVE_THROTTLE_MS) {
            Log.d(TAG, "SaveProgress (Throttled): $position")
            lastSaveTimeMs = currentTimeMs // Обновляем время
            // Запускаем реальное сохранение
            saveProgressJob = serviceScope.launch {
                try {
                    saveListenProgressUseCase(bookId, chapterId, position)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        // --- 3. Debounce (на паузу / seek) ---
        else if (isDebounce) {
            // 10 секунд не прошло. Просто планируем сохранение
            // (оно выполнится, если 1 сек не будет новых вызовов)
            saveProgressJob = serviceScope.launch {
                delay(SAVE_DEBOUNCE_MS)
                Log.d(TAG, "SaveProgress (Debounced): $position")
                // Проверяем, что ID не изменились, пока мы ждали
                if (currentBookId == bookId && currentChapterId == chapterId) {
                    saveListenProgressUseCase(bookId, chapterId, position)
                    // Также обновляем время, т.к. debounce-сохранение тоже считается
                    lastSaveTimeMs = System.currentTimeMillis()
                }
            }
        }
        // else - (Throttle, но 10 сек не прошло) -> просто ничего не делаем.
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

        // Не вызываем startForeground, если мы очистили сервис
        if (_playerStateFlow.value.loadedChapterId.isNotEmpty()) {
            startForeground(NOTIFICATION_ID, notification)
        }
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
        Log.d(TAG, "onDestroy: Service destroyed. Forcing final save.")
        // --- 9. Финальное сохранение при уничтожении ---
        triggerSave(
            position = _playerStateFlow.value.currentPosition,
            isDebounce = false,
            isFinalSave = true
        )
        serviceScope.cancel()
        player.release()
        mediaSession.release()
    }
}


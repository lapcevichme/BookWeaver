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
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.lapcevichme.bookweaver.core.PlayerState
import com.lapcevichme.bookweaver.domain.model.PlaybackEntry
import com.lapcevichme.bookweaver.domain.usecase.player.GetAmbientTrackPathUseCase
import com.lapcevichme.bookweaver.domain.usecase.player.GetAmbientVolumeUseCase
import com.lapcevichme.bookweaver.domain.usecase.player.GetChapterPlaybackDataUseCase
import com.lapcevichme.bookweaver.domain.usecase.player.GetPlaybackSpeedUseCase
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class MediaPlayerService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    //
    @Inject
    lateinit var getChapterPlaybackDataUseCase: GetChapterPlaybackDataUseCase

    @Inject
    lateinit var getAmbientTrackPathUseCase: GetAmbientTrackPathUseCase

    //
    @Inject
    lateinit var saveListenProgressUseCase: SaveListenProgressUseCase

    @Inject
    lateinit var getPlaybackSpeedUseCase: GetPlaybackSpeedUseCase

    @Inject
    lateinit var getAmbientVolumeUseCase: GetAmbientVolumeUseCase

    private lateinit var player: ExoPlayer
    private lateinit var ambientPlayer: ExoPlayer
    private lateinit var mediaSession: MediaSessionCompat
    private var placeholderBitmap: Bitmap? = null

    private var currentBookId: String? = null
    private var currentChapterId: String? = null

    private var currentPlaybackData: List<PlaybackEntry> = emptyList()

    private var currentAmbientName: String = "none"
    private var currentAudioDirectoryPath: String = ""
    private var ambientVolume: Float = 0.5f

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
        ambientPlayer = ExoPlayer.Builder(this).build()
        ambientPlayer.repeatMode = Player.REPEAT_MODE_ONE

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
                    if (ambientPlayer.playbackState == Player.STATE_IDLE || !ambientPlayer.isPlaying) {
                        if (ambientPlayer.mediaItemCount > 0) ambientPlayer.play()
                    }
                } else {
                    stopPositionUpdates()
                    ambientPlayer.pause()
                    triggerSave(
                        position = _playerStateFlow.value.currentPosition,
                        isDebounce = true,
                        isFinalSave = false
                    )
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    player.seekTo(0, 0L)
                    player.playWhenReady = false
                    ambientPlayer.pause()
                }
                updatePlayerState()
                updateNotification()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentMediaItemIndex = player.currentMediaItemIndex
                basePositionOffsetMs =
                    currentPlaybackData.getOrNull(currentMediaItemIndex)?.startMs ?: 0L
                Log.d(
                    TAG,
                    "MediaItem transition. New index: $currentMediaItemIndex, new offset: $basePositionOffsetMs"
                )
                checkAmbient()
                updatePlayerState()
            }
        })

        serviceScope.launch {
            getPlaybackSpeedUseCase().collectLatest { speed ->
                // Сохраняем playWhenReady при смене скорости
                val wasPlaying = player.playWhenReady
                player.playbackParameters = PlaybackParameters(speed)
                player.playWhenReady = wasPlaying // Восстанавливаем

                updatePlayerState()
            }
        }

        serviceScope.launch {
            getAmbientVolumeUseCase().collectLatest { volume ->
                Log.d(TAG, "Setting ambient volume: $volume")
                ambientVolume = volume
                ambientPlayer.volume = volume
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // !! ANR FIX !!
        // Мы обязаны немедленно вызвать startForeground, если сервис был запущен через startForegroundService().
        // Это уведомление будет почти сразу заменено или удалено вызовом updateNotification() ниже.
        val minimalNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BookWeaver")
            .setContentText("Инициализация плеера...")
            .setSmallIcon(android.R.drawable.ic_media_play) // TODO: Заменить на иконку приложения
            .build()
        startForeground(NOTIFICATION_ID, minimalNotification)

        updateNotification()
        when (intent?.action) {
            ACTION_PLAY -> play()
            ACTION_PAUSE -> pause()
            ACTION_PREVIOUS -> player.seekToPreviousMediaItem()
            ACTION_NEXT -> player.seekToNextMediaItem()
            ACTION_STOP -> {
                Log.d(TAG, "ACTION_STOP received. Forcing final save and stopping.")
                triggerSave(
                    position = _playerStateFlow.value.currentPosition,
                    isDebounce = false,
                    isFinalSave = true
                )
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "onTaskRemoved: App swiped away. Forcing final save.")
        // Принудительное, немедленное сохранение
        triggerSave(
            position = _playerStateFlow.value.currentPosition,
            isDebounce = false,
            isFinalSave = true
        )
    }

    @OptIn(UnstableApi::class)
    fun setMedia(
        bookId: String,
        chapterId: String,
        chapterTitle: String,
        coverPath: String?,
        playWhenReady: Boolean,
        seekToPositionMs: Long? = null
    ) {
        Log.d(
            TAG,
            "setMedia called for $bookId / $chapterId. Play: $playWhenReady, Seek: $seekToPositionMs"
        )

        // Сохраняем прогресс старой главы, если она была
        if (currentChapterId != null && _playerStateFlow.value.currentPosition > 0) {
            Log.d(TAG, "setMedia: Saving progress for old chapter $currentChapterId")
            triggerSave(
                position = _playerStateFlow.value.currentPosition,
                isDebounce = false,
                isFinalSave = true
            )
        }

        // Немедленно останавливаем все и очищаем медиа
        player.stop()
        player.clearMediaItems()
        ambientPlayer.stop()
        ambientPlayer.clearMediaItems()

        // Сбрасываем внутреннее состояние
        currentBookId = bookId
        currentChapterId = chapterId
        currentPlaybackData = emptyList()
        currentMediaItemIndex = 0
        basePositionOffsetMs = 0L
        totalChapterDurationMs = 0L
        currentAmbientName = "none"
        currentAudioDirectoryPath = ""

        serviceScope.launch {
            getChapterPlaybackDataUseCase(bookId, chapterId).fold(
                onSuccess = { (playbackData, audioDirectoryPath) ->
                    if (playbackData.isEmpty()) {
                        throw Exception("Merged PlaybackData is empty")
                    }

                    currentPlaybackData = playbackData
                    currentAudioDirectoryPath = audioDirectoryPath
                    totalChapterDurationMs = playbackData.lastOrNull()?.endMs ?: 0L

                    val mediaItems = mutableListOf<MediaItem>()
                    for (entry in playbackData) {
                        val audioFile = File(audioDirectoryPath, entry.audioFile)
                        if (!audioFile.exists()) {
                            Log.w(TAG, "Missing audio file: ${entry.audioFile}")
                            continue
                        }
                        mediaItems.add(MediaItem.fromUri(audioFile.toUri()))
                    }
                    player.setMediaItems(mediaItems)

                    if (seekToPositionMs != null) {
                        Log.d(TAG, "setMedia: Перемотка (до prepare) на $seekToPositionMs")
                        seekTo(seekToPositionMs)
                    } else {
                        checkAmbient()
                    }

                    player.playWhenReady = playWhenReady
                    player.prepare()

                    var loadedBitmap: Bitmap? = null
                    if (coverPath != null) {
                        try {
                            loadedBitmap = BitmapFactory.decodeFile(coverPath)
                            placeholderBitmap = loadedBitmap
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to load cover image", e)
                        }
                    }

                    // Устанавливаем НОВОЕ чистое состояние
                    _playerStateFlow.value = PlayerState(
                        fileName = chapterTitle,
                        albumArt = loadedBitmap,
                        subtitlesEnabled = true,
                        duration = totalChapterDurationMs,
                        currentPosition = seekToPositionMs ?: 0L,
                        loadedChapterId = chapterId
                    )

                    updatePlayerState() // Обновляем isPlaying, speed и т.д.
                    updateNotification()
                },
                onFailure = { e ->
                    // При ошибке - полная очистка
                    Log.e(TAG, "Error setting media source, probably no subtitles", e)

                    // Вызываем полную очистку. Она сбросит _playerStateFlow в PlayerState()
                    // и уберет нотификацию.
                    stopAndClear()

                    // Обновляем состояние (уже чистое) ошибкой, чтобы UI (ViewModel)
                    // мог ее поймать и сбросить loadCommand.
                    _playerStateFlow.value = PlayerState(
                        error = "Аудио для этой главы недоступно."
                    )
                }
            )
        }
    }

    fun play() {
        player.play()
        if (ambientPlayer.mediaItemCount > 0) ambientPlayer.play() //
    }

    fun pause() {
        player.pause()
        ambientPlayer.pause()
    }

    fun togglePlayPause() {
        if (player.isPlaying) {
            pause()
        } else {
            play()
        }
    }

    fun seekTo(position: Long) {
        if (currentPlaybackData.isEmpty()) return

        val clampedPosition = position.coerceIn(0L, totalChapterDurationMs)

        val targetItemIndex = currentPlaybackData.indexOfLast { clampedPosition >= it.startMs }
            .coerceAtLeast(0)

        val targetItem =
            currentPlaybackData.getOrNull(targetItemIndex) ?: currentPlaybackData.first()
        val relativePosition = (clampedPosition - targetItem.startMs).coerceAtLeast(0L)

        Log.d(
            TAG,
            "seekTo: pos=$clampedPosition, targetIndex=$targetItemIndex, relativePos=$relativePosition"
        )

        basePositionOffsetMs = targetItem.startMs
        currentMediaItemIndex = targetItemIndex

        player.seekTo(targetItemIndex, relativePosition)

        checkAmbient() //

        if (player.playbackState != Player.STATE_IDLE) {
            updatePlayerState()
            triggerSave(
                position = clampedPosition,
                isDebounce = true,
                isFinalSave = false
            )
        }
    }

    /**
     * Проверяет, не изменился ли эмбиент для текущего PlaybackEntry.
     */
    private fun checkAmbient() {
        val entry = currentPlaybackData.getOrNull(currentMediaItemIndex) ?: return
        val newAmbientName = entry.ambient //

        if (newAmbientName != currentAmbientName) {
            Log.d(TAG, "Ambient changed from '$currentAmbientName' to '$newAmbientName'")
            currentAmbientName = newAmbientName
            updateAmbientPlayer(newAmbientName)
        }
    }

    /**
     * Загружает и запускает эмбиент-трек по его имени.
     */
    @OptIn(UnstableApi::class)
    private fun updateAmbientPlayer(ambientName: String) {
        ambientPlayer.stop()
        ambientPlayer.clearMediaItems()

        if (ambientName == "none" || currentBookId == null) {
            return
        }

        serviceScope.launch {
            getAmbientTrackPathUseCase(currentBookId!!, ambientName).fold(
                onSuccess = { path ->
                    if (path == null) {
                        Log.w(TAG, "Ambient track '$ambientName' not found at path.")
                        return@fold
                    }

                    try {
                        Log.d(TAG, "Loading ambient: $path")
                        ambientPlayer.setMediaItem(MediaItem.fromUri(File(path).toUri()))
                        ambientPlayer.volume = ambientVolume
                        ambientPlayer.playWhenReady = player.isPlaying //
                        ambientPlayer.prepare()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load ambient from path: $path", e)
                    }
                },
                onFailure = { e ->
                    Log.e(TAG, "Error getting ambient path for '$ambientName'", e)
                }
            )
        }
    }


    fun toggleSubtitles(enabled: Boolean) {
        _playerStateFlow.value = _playerStateFlow.value.copy(subtitlesEnabled = enabled)
        updatePlayerState()
    }

    fun stopAndClear() {
        Log.d(TAG, "stopAndClear: Stopping player, clearing media, and resetting state.")
        triggerSave(
            position = _playerStateFlow.value.currentPosition,
            isDebounce = false,
            isFinalSave = true
        )
        // !! Убеждаемся, что сброс состояния происходит ДО остановки плеера !!
        // Это предотвратит вызов updateNotification() с "грязным" состоянием.
        _playerStateFlow.value = PlayerState()

        player.pause(); player.stop(); player.clearMediaItems()
        ambientPlayer.pause(); ambientPlayer.stop(); ambientPlayer.clearMediaItems()

        currentBookId = null
        currentChapterId = null
        currentPlaybackData = emptyList()
        totalChapterDurationMs = 0L
        basePositionOffsetMs = 0L
        currentMediaItemIndex = 0
        currentAmbientName = "none"
        currentAudioDirectoryPath = ""

        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.d(TAG, "stopAndClear: State has been cleared.")
    }

    private fun updatePlayerState() {
        if (player.playbackState == Player.STATE_IDLE || currentPlaybackData.isEmpty()) return

        val currentItemOffset =
            currentPlaybackData.getOrNull(player.currentMediaItemIndex)?.startMs ?: 0L
        basePositionOffsetMs = currentItemOffset

        val absolutePosition = basePositionOffsetMs + player.currentPosition

        val currentSubtitleText: CharSequence =
            if (_playerStateFlow.value.subtitlesEnabled) {
                val currentEntry = currentPlaybackData.firstOrNull {
                    absolutePosition >= it.startMs && absolutePosition < it.endMs
                } ?: currentPlaybackData.lastOrNull {
                    it == currentPlaybackData.last() && absolutePosition >= it.startMs && absolutePosition <= it.endMs
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
            loadedChapterId = currentChapterId ?: ""
        )
    }

    private fun buildKaraokeSubtitle(entry: PlaybackEntry, absolutePosition: Long): CharSequence {
        try {
            val spannable = SpannableString(entry.text)
            var charIndex = 0

            for (word in entry.words) {
                val startChar = charIndex
                val endChar = (startChar + word.word.length).coerceAtMost(spannable.length)

                if (startChar >= endChar) continue

                val wordAbsoluteStart = word.start
                val wordAbsoluteEnd = word.end

                if (absolutePosition >= wordAbsoluteStart && absolutePosition <= wordAbsoluteEnd) {
                    spannable.setSpan(
                        StyleSpan(Typeface.BOLD),
                        startChar,
                        endChar,
                        SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }

                charIndex = endChar
                if (charIndex < spannable.length && spannable[charIndex] == ' ') {
                    charIndex++
                }
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
                checkAmbient()
                triggerSave(
                    position = _playerStateFlow.value.currentPosition,
                    isDebounce = false,
                    isFinalSave = false
                )
                delay(100)
            }
        }
    }
    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
    }

    private fun triggerSave(position: Long, isDebounce: Boolean, isFinalSave: Boolean) {
        val bookId = currentBookId
        val chapterId = currentChapterId
        if (bookId == null || chapterId == null || position == 0L) {
            return
        }

        saveProgressJob?.cancel()
        val currentTimeMs = System.currentTimeMillis()

        if (isFinalSave) {
            Log.d(TAG, "SaveProgress (Final): $position")
            serviceScope.launch(Dispatchers.IO) { //
                try {
                    saveListenProgressUseCase(bookId, chapterId, position)
                    lastSaveTimeMs = System.currentTimeMillis()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            return
        }

        if (!isDebounce && currentTimeMs - lastSaveTimeMs > SAVE_THROTTLE_MS) {
            Log.d(TAG, "SaveProgress (Throttled): $position")
            lastSaveTimeMs = currentTimeMs
            saveProgressJob = serviceScope.launch(Dispatchers.IO) { //
                try {
                    saveListenProgressUseCase(bookId, chapterId, position)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } else if (isDebounce) {
            saveProgressJob = serviceScope.launch {
                delay(SAVE_DEBOUNCE_MS)
                Log.d(TAG, "SaveProgress (Debounced): $position")
                if (currentBookId == bookId && currentChapterId == chapterId) {
                    withContext(Dispatchers.IO) { //
                        saveListenProgressUseCase(bookId, chapterId, position)
                    }
                    lastSaveTimeMs = System.currentTimeMillis()
                }
            }
        }
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
                player.playbackParameters.speed
            )
            .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SEEK_TO or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_STOP)
            .build()
        mediaSession.setPlaybackState(playbackState)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(_playerStateFlow.value.fileName.ifEmpty { "Аудиоплеер" })
            .setContentText("BookWeaver")
            .setSmallIcon(android.R.drawable.ic_media_play) // TODO: Заменить на иконку приложения
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

        if (_playerStateFlow.value.loadedChapterId.isNotEmpty()) {
            // Если у нас есть контент, мы ОБНОВЛЯЕМ уведомление
            startForeground(NOTIFICATION_ID, notification)
        } else {
            // Если контента нет, мы УБИРАЕМ сервис из foreground
            // (но он продолжит жить, если привязан)
            stopForeground(STOP_FOREGROUND_REMOVE) //
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
        triggerSave(
            position = _playerStateFlow.value.currentPosition,
            isDebounce = false,
            isFinalSave = true
        )
        serviceScope.cancel()
        player.release()
        ambientPlayer.release()
        mediaSession.release()
    }
}
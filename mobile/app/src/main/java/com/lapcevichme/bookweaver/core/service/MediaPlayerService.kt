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
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ConcatenatingMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.lapcevichme.bookweaver.core.PlayerState
import com.lapcevichme.bookweaver.domain.model.PlaybackEntry
import com.lapcevichme.bookweaver.domain.repository.ServerRepository
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
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

@AndroidEntryPoint
class MediaPlayerService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    @Inject lateinit var getChapterPlaybackDataUseCase: GetChapterPlaybackDataUseCase
    @Inject lateinit var getAmbientTrackPathUseCase: GetAmbientTrackPathUseCase
    @Inject lateinit var saveListenProgressUseCase: SaveListenProgressUseCase
    @Inject lateinit var getPlaybackSpeedUseCase: GetPlaybackSpeedUseCase
    @Inject lateinit var getAmbientVolumeUseCase: GetAmbientVolumeUseCase
    @Inject lateinit var serverRepository: ServerRepository

    private lateinit var player: ExoPlayer
    private lateinit var ambientPlayer: ExoPlayer
    private lateinit var mediaSession: MediaSessionCompat
    private var placeholderBitmap: Bitmap? = null

    private var currentBookId: String? = null
    private var currentChapterId: String? = null

    private var currentPlaybackData: List<PlaybackEntry> = emptyList()
    private var currentAmbientName: String = "none"
    private var ambientVolume: Float = 0.5f

    private var currentMediaItemIndex = 0
    private var basePositionOffsetMs = 0L
    private var totalChapterDurationMs = 0L

    private val _playerStateFlow = MutableStateFlow(PlayerState())
    val playerStateFlow = _playerStateFlow.asStateFlow()

    private var saveProgressJob: Job? = null
    private var lastSaveTimeMs: Long = 0
    private val SAVE_THROTTLE_MS = 10_000L
    private val SAVE_DEBOUNCE_MS = 1_000L
    private val CONNECTION_TIMEOUT_MS = 30_000
    private val READ_TIMEOUT_MS = 30_000

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

        // Настройка буферизации для улучшения работы в медленных сетях
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                30_000, // Минимальный буфер (30 сек)
                120_000, // Максимальный буфер (2 мин)
                2_000,  // Буфер для старта (2 сек)
                5_000   // Буфер после ребаферинга (5 сек)
            )
            .build()

        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .build()

        ambientPlayer = ExoPlayer.Builder(this).build()
        ambientPlayer.repeatMode = Player.REPEAT_MODE_ONE

        mediaSession = MediaSessionCompat(this, "AudioPlayerSession")
        mediaSession.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() { super.onPlay(); play() }
            override fun onPause() { super.onPause(); pause() }
            override fun onSeekTo(pos: Long) { super.onSeekTo(pos); seekTo(pos) }
            override fun onStop() { super.onStop(); stopSelf() }
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
                if (playbackState == Player.STATE_READY && player.duration > 0) {
                    if (player.currentTimeline.windowCount == 1) {
                        totalChapterDurationMs = player.duration
                    }
                }

                if (playbackState == Player.STATE_ENDED) {
                    player.seekTo(0, 0L)
                    player.playWhenReady = false
                    ambientPlayer.pause()
                }
                updatePlayerState()
                updateNotification()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (player.currentTimeline.windowCount > 1) {
                    currentMediaItemIndex = player.currentMediaItemIndex
                    basePositionOffsetMs = currentPlaybackData.getOrNull(currentMediaItemIndex)?.startMs ?: 0L
                }
                checkAmbient()
                updatePlayerState()
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "ExoPlayer Error: ${error.message}", error)
                _playerStateFlow.value = _playerStateFlow.value.copy(
                    error = "Ошибка воспроизведения: ${error.message}. Проверьте сеть.",
                    isPlaying = false
                )
                updateNotification()
                stopPositionUpdates()
            }
        })

        serviceScope.launch {
            getPlaybackSpeedUseCase().collectLatest { speed ->
                val wasPlaying = player.playWhenReady
                player.playbackParameters = PlaybackParameters(speed)
                player.playWhenReady = wasPlaying
                updatePlayerState()
            }
        }

        serviceScope.launch {
            getAmbientVolumeUseCase().collectLatest { volume ->
                ambientVolume = volume
                ambientPlayer.volume = volume
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val minimalNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BookWeaver")
            .setContentText("Загрузка...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        startForeground(NOTIFICATION_ID, minimalNotification)

        updateNotification()
        when (intent?.action) {
            ACTION_PLAY -> play()
            ACTION_PAUSE -> pause()
            ACTION_PREVIOUS -> player.seekToPreviousMediaItem()
            ACTION_NEXT -> player.seekToNextMediaItem()
            ACTION_STOP -> {
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
        Log.d(TAG, "setMedia: $chapterId")

        if (currentChapterId != null && _playerStateFlow.value.currentPosition > 0) {
            triggerSave(
                position = _playerStateFlow.value.currentPosition,
                isDebounce = false,
                isFinalSave = true
            )
        }

        player.stop()
        player.clearMediaItems()
        ambientPlayer.stop()
        ambientPlayer.clearMediaItems()

        currentBookId = bookId
        currentChapterId = chapterId
        currentPlaybackData = emptyList()
        currentMediaItemIndex = 0
        basePositionOffsetMs = 0L
        totalChapterDurationMs = 0L
        currentAmbientName = "none"

        // Сбрасываем текущую обложку (bitmap), чтобы не показывать старую
        placeholderBitmap = null

        _playerStateFlow.value = _playerStateFlow.value.copy(
            isLoading = true,
            fileName = chapterTitle,
            loadedChapterId = chapterId,
            albumArt = null
        )

        serviceScope.launch {
            getChapterPlaybackDataUseCase(bookId, chapterId).fold(
                onSuccess = { (playbackData, audioPath) ->
                    if (playbackData.isEmpty()) {
                        _playerStateFlow.value = PlayerState(error = "Нет данных для воспроизведения")
                        return@fold
                    }

                    currentPlaybackData = playbackData
                    totalChapterDurationMs = playbackData.lastOrNull()?.endMs ?: 0L

                    val isRemote = audioPath.startsWith("http")
                    val isSingleFile = (isRemote && (audioPath.endsWith(".mp3") || audioPath.endsWith(".m4a") || audioPath.endsWith(".wav"))) ||
                            (!isRemote && File(audioPath).isFile)

                    val mediaSource = if (isSingleFile) {
                        prepareSingleFileMediaSource(audioPath, isRemote)
                    } else {
                        if (isRemote) {
                            prepareRemotePlaylistMediaSource(playbackData, audioPath)
                        } else {
                            prepareLocalPlaylistMediaSource(playbackData, audioPath)
                        }
                    }

                    if (mediaSource == null) {
                        stopAndClear()
                        _playerStateFlow.value = PlayerState(error = "Не удалось подготовить аудио.")
                        return@fold
                    }

                    withContext(Dispatchers.Main) {
                        player.setMediaSource(mediaSource)

                        if (seekToPositionMs != null && seekToPositionMs > 0) {
                            if (isSingleFile) {
                                player.seekTo(seekToPositionMs)
                            } else {
                                seekTo(seekToPositionMs)
                            }
                        } else {
                            checkAmbient()
                        }

                        player.playWhenReady = playWhenReady
                        player.prepare()
                    }

                    _playerStateFlow.value = _playerStateFlow.value.copy(
                        isLoading = false,
                        fileName = chapterTitle,
                        duration = totalChapterDurationMs,
                        loadedChapterId = chapterId
                    )
                    updateNotification()

                    // Загружаем обложку в фоне. Она обновит стейт сама, когда (и если) загрузится.
                    // Если сервер медленный, это не заблокирует управление плеером.
                    loadCover(coverPath, isRemote, audioPath)
                },
                onFailure = { e ->
                    Log.e(TAG, "Error setting media", e)
                    stopAndClear()
                    _playerStateFlow.value = PlayerState(error = "Ошибка: ${e.message}")
                }
            )
        }
    }

    @OptIn(UnstableApi::class)
    private fun prepareSingleFileMediaSource(path: String, isRemote: Boolean): MediaSource {
        basePositionOffsetMs = 0L
        currentMediaItemIndex = 0
        val mediaItem = MediaItem.fromUri(path)
        val dataSourceFactory: androidx.media3.datasource.DataSource.Factory = if (isRemote) {
            val connection = serverRepository.getCurrentConnection()
            val headers = if (connection != null) mapOf("Authorization" to "Bearer ${connection.token}") else emptyMap()
            DefaultHttpDataSource.Factory()
                .setUserAgent("BookWeaver-Android")
                .setConnectTimeoutMs(CONNECTION_TIMEOUT_MS)
                .setReadTimeoutMs(READ_TIMEOUT_MS)
                .setDefaultRequestProperties(headers)
        } else {
            DefaultDataSource.Factory(this@MediaPlayerService)
        }
        return DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(mediaItem)
    }

    @OptIn(UnstableApi::class)
    private suspend fun prepareLocalPlaylistMediaSource(
        playbackData: List<PlaybackEntry>,
        basePath: String
    ): MediaSource? = withContext(Dispatchers.IO) {
        val sources = mutableListOf<MediaSource>()
        val dataSourceFactory = DefaultMediaSourceFactory(this@MediaPlayerService)
        for (entry in playbackData) {
            if (entry.audioFile.isBlank()) continue
            val file = File(basePath, entry.audioFile)
            if (!file.exists()) continue
            sources.add(dataSourceFactory.createMediaSource(MediaItem.fromUri(file.toUri())))
        }
        if (sources.isEmpty()) return@withContext null
        return@withContext ConcatenatingMediaSource(*sources.toTypedArray())
    }

    @OptIn(UnstableApi::class)
    private suspend fun prepareRemotePlaylistMediaSource(
        playbackData: List<PlaybackEntry>,
        baseUrl: String
    ): MediaSource? = withContext(Dispatchers.IO) {
        val sources = mutableListOf<MediaSource>()
        val connection = serverRepository.getCurrentConnection()
        val headers = if (connection != null) mapOf("Authorization" to "Bearer ${connection.token}") else emptyMap()
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("BookWeaver-Android")
            .setConnectTimeoutMs(CONNECTION_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)
            .setDefaultRequestProperties(headers)
        val mediaSourceFactory = DefaultMediaSourceFactory(httpDataSourceFactory)
        val cleanBaseUrl = baseUrl.removeSuffix("/")
        for (entry in playbackData) {
            if (entry.audioFile.isBlank()) continue
            val url = "$cleanBaseUrl/${entry.audioFile}"
            sources.add(mediaSourceFactory.createMediaSource(MediaItem.fromUri(url)))
        }
        if (sources.isEmpty()) return@withContext null
        return@withContext ConcatenatingMediaSource(*sources.toTypedArray())
    }

    fun play() { player.play(); if (ambientPlayer.mediaItemCount > 0) ambientPlayer.play() }
    fun pause() { player.pause(); ambientPlayer.pause() }
    fun togglePlayPause() { if (player.isPlaying) pause() else play() }

    fun seekTo(position: Long) {
        val isSingleFile = player.currentTimeline.windowCount <= 1
        if (isSingleFile) {
            player.seekTo(position)
        } else {
            if (currentPlaybackData.isEmpty()) return
            val safeDuration = if (totalChapterDurationMs > 0) totalChapterDurationMs else Long.MAX_VALUE
            val clampedPosition = position.coerceIn(0L, safeDuration)
            val targetItemIndex = currentPlaybackData.indexOfLast { clampedPosition >= it.startMs }.coerceAtLeast(0)
            val targetItem = currentPlaybackData.getOrNull(targetItemIndex) ?: currentPlaybackData.first()
            val relativePosition = (clampedPosition - targetItem.startMs).coerceAtLeast(0L)
            basePositionOffsetMs = targetItem.startMs
            currentMediaItemIndex = targetItemIndex
            player.seekTo(targetItemIndex, relativePosition)
        }
        checkAmbient()
        if (player.playbackState != Player.STATE_IDLE) {
            updatePlayerState()
            triggerSave(position = position, isDebounce = true, isFinalSave = false)
        }
    }

    private fun checkAmbient() {
        val currentPosition = _playerStateFlow.value.currentPosition
        val entry = currentPlaybackData.firstOrNull {
            currentPosition >= it.startMs && currentPosition < it.endMs
        } ?: currentPlaybackData.getOrNull(currentMediaItemIndex)

        if (entry == null) return

        val newAmbientName = entry.ambient
        if (newAmbientName != currentAmbientName) {
            currentAmbientName = newAmbientName
            updateAmbientPlayer(newAmbientName)
        }
    }

    @OptIn(UnstableApi::class)
    private fun updateAmbientPlayer(ambientName: String) {
        ambientPlayer.stop()
        ambientPlayer.clearMediaItems()

        // Сохраняем локальную копию ID книги.
        // Это предотвращает краш, если currentBookId станет null (через stopAndClear) пока корутина запускается.
        val bookId = currentBookId

        if (ambientName == "none" || bookId == null) return

        serviceScope.launch {
            if (bookId != currentBookId) return@launch

            getAmbientTrackPathUseCase(bookId, ambientName).fold(
                onSuccess = { path ->
                    if (path == null) return@fold
                    try {
                        val connection = serverRepository.getCurrentConnection()
                        val headers = mutableMapOf<String, String>()
                        if (connection != null) {
                            headers["Authorization"] = "Bearer ${connection.token}"
                        }
                        val dataSourceFactory: androidx.media3.datasource.DataSource.Factory = if (path.startsWith("http")) {
                            DefaultHttpDataSource.Factory()
                                .setUserAgent("BookWeaver-Android")
                                .setConnectTimeoutMs(CONNECTION_TIMEOUT_MS)
                                .setReadTimeoutMs(READ_TIMEOUT_MS)
                                .setDefaultRequestProperties(headers)
                        } else {
                            DefaultDataSource.Factory(this@MediaPlayerService)
                        }
                        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
                        val mediaSource: MediaSource = if (path.startsWith("http")) {
                            mediaSourceFactory.createMediaSource(MediaItem.fromUri(path))
                        } else {
                            val file = File(path)
                            if (file.exists()) {
                                mediaSourceFactory.createMediaSource(MediaItem.fromUri(file.toUri()))
                            } else {
                                return@fold
                            }
                        }
                        ambientPlayer.setMediaSource(mediaSource)
                        ambientPlayer.volume = ambientVolume
                        ambientPlayer.playWhenReady = player.isPlaying
                        ambientPlayer.prepare()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load ambient", e)
                    }
                },
                onFailure = { e -> Log.e(TAG, "Error ambient", e) }
            )
        }
    }

    fun toggleSubtitles(enabled: Boolean) {
        _playerStateFlow.value = _playerStateFlow.value.copy(subtitlesEnabled = enabled)
        updatePlayerState()
    }

    fun stopAndClear(resetUI: Boolean = true) {
        triggerSave(position = _playerStateFlow.value.currentPosition, isDebounce = false, isFinalSave = true)
        player.stop()
        player.clearMediaItems()
        ambientPlayer.stop()
        ambientPlayer.clearMediaItems()
        if (resetUI) {
            _playerStateFlow.value = PlayerState()
            currentBookId = null; currentChapterId = null
            currentPlaybackData = emptyList()
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun updatePlayerState() {
        if (player.playbackState == Player.STATE_IDLE && totalChapterDurationMs == 0L) return

        val absolutePosition = if (player.currentTimeline.windowCount <= 1) {
            player.currentPosition
        } else {
            val currentItemOffset = currentPlaybackData.getOrNull(player.currentMediaItemIndex)?.startMs ?: 0L
            currentItemOffset + player.currentPosition
        }

        val currentSubtitleText: CharSequence = if (_playerStateFlow.value.subtitlesEnabled) {
            val currentEntry = currentPlaybackData.firstOrNull {
                absolutePosition >= it.startMs && absolutePosition < it.endMs
            } ?: currentPlaybackData.lastOrNull {
                it == currentPlaybackData.last() && absolutePosition >= it.startMs
            }
            if (currentEntry != null && currentEntry.words.isNotEmpty()) {
                buildKaraokeSubtitle(currentEntry, absolutePosition)
            } else {
                currentEntry?.text ?: ""
            }
        } else {
            ""
        }

        _playerStateFlow.value = _playerStateFlow.value.copy(
            isPlaying = player.isPlaying,
            duration = if (totalChapterDurationMs > 0) totalChapterDurationMs else 0L,
            currentPosition = absolutePosition,
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
                if (absolutePosition >= word.start && absolutePosition <= word.end) {
                    spannable.setSpan(StyleSpan(Typeface.BOLD), startChar, endChar, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                charIndex = endChar
                if (charIndex < spannable.length && spannable[charIndex] == ' ') { charIndex++ }
            }
            return spannable
        } catch (e: Exception) {
            return entry.text
        }
    }

    private suspend fun loadCover(path: String?, isRemote: Boolean, baseRemoteUrl: String?) {
        if (path.isNullOrEmpty()) {
            placeholderBitmap = null
            withContext(Dispatchers.Main) {
                _playerStateFlow.value = _playerStateFlow.value.copy(albumArt = null)
                updateNotification()
            }
            return
        }

        withContext(Dispatchers.IO) {
            try {
                var bitmap: Bitmap? = null

                // Явно проверяем, является ли это URL
                if (path.startsWith("http")) {
                    val connection = serverRepository.getCurrentConnection()
                    val url = URL(path)
                    val conn = url.openConnection() as HttpURLConnection

                    if (connection != null) {
                        conn.setRequestProperty("Authorization", "Bearer ${connection.token}")
                    }

                    conn.connectTimeout = CONNECTION_TIMEOUT_MS
                    conn.readTimeout = READ_TIMEOUT_MS

                    conn.connect()

                    // Проверяем код ответа
                    if (conn.responseCode == 200) {
                        conn.inputStream.use {
                            bitmap = BitmapFactory.decodeStream(it)
                        }
                    } else {
                        Log.w(TAG, "Failed to load cover: ${conn.responseCode} ${conn.responseMessage}")
                    }
                }
                // Если не http, проверяем файл локально
                else if (File(path).exists()) {
                    bitmap = BitmapFactory.decodeFile(path)
                }
                // Если файл не найден, но включен remote режим - пробуем собрать URL (fallback)
                else if (isRemote) {
                    val host = baseRemoteUrl?.substringBefore("/static") ?: ""
                    val cleanHost = host.removeSuffix("/")
                    val cleanPath = path.removePrefix("/")
                    val fullUrl = "$cleanHost/$cleanPath"

                    if (fullUrl.startsWith("http")) {
                        val connection = serverRepository.getCurrentConnection()
                        val url = URL(fullUrl)
                        val conn = url.openConnection() as HttpURLConnection
                        if (connection != null) {
                            conn.setRequestProperty("Authorization", "Bearer ${connection.token}")
                        }

                        conn.connectTimeout = CONNECTION_TIMEOUT_MS
                        conn.readTimeout = READ_TIMEOUT_MS

                        conn.connect()
                        if (conn.responseCode == 200) {
                            conn.inputStream.use { bitmap = BitmapFactory.decodeStream(it) }
                        }
                    }
                }

                placeholderBitmap = bitmap

                withContext(Dispatchers.Main) {
                    _playerStateFlow.value = _playerStateFlow.value.copy(albumArt = bitmap)
                    updateNotification()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Cover load failed: $path", e)
                // Можно сбросить обложку, если загрузка не удалась
                placeholderBitmap = null
                withContext(Dispatchers.Main) {
                    _playerStateFlow.value = _playerStateFlow.value.copy(albumArt = null)
                    updateNotification()
                }
            }
        }
    }

    private var positionUpdateJob: Job? = null
    private fun startPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = serviceScope.launch {
            while (isActive) {
                updatePlayerState()
                checkAmbient()
                triggerSave(position = _playerStateFlow.value.currentPosition, isDebounce = false, isFinalSave = false)
                delay(100)
            }
        }
    }
    private fun stopPositionUpdates() { positionUpdateJob?.cancel() }

    private fun triggerSave(position: Long, isDebounce: Boolean, isFinalSave: Boolean) {
        val bookId = currentBookId ?: return
        val chapterId = currentChapterId ?: return
        if (position == 0L) return

        saveProgressJob?.cancel()
        val currentTimeMs = System.currentTimeMillis()

        if (isFinalSave || (!isDebounce && currentTimeMs - lastSaveTimeMs > SAVE_THROTTLE_MS)) {
            serviceScope.launch(Dispatchers.IO) {
                try { saveListenProgressUseCase(bookId, chapterId, position) } catch(e:Exception){}
            }
            if (!isFinalSave) lastSaveTimeMs = currentTimeMs
        } else if (isDebounce) {
            saveProgressJob = serviceScope.launch {
                delay(SAVE_DEBOUNCE_MS)
                withContext(Dispatchers.IO) { try { saveListenProgressUseCase(bookId, chapterId, position) } catch(e:Exception){} }
                lastSaveTimeMs = System.currentTimeMillis()
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun updateNotification() {
        val isPlaying = player.isPlaying
        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action(android.R.drawable.ic_media_pause, "Pause", createPendingIntent(ACTION_PAUSE))
        } else {
            NotificationCompat.Action(android.R.drawable.ic_media_play, "Play", createPendingIntent(ACTION_PLAY))
        }
        val prevAction = NotificationCompat.Action(android.R.drawable.ic_media_previous, "Prev", createPendingIntent(ACTION_PREVIOUS))
        val nextAction = NotificationCompat.Action(android.R.drawable.ic_media_next, "Next", createPendingIntent(ACTION_NEXT))

        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, _playerStateFlow.value.fileName)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, totalChapterDurationMs)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, placeholderBitmap)
            .build()
        mediaSession.setMetadata(metadata)

        val playbackState = PlaybackStateCompat.Builder()
            .setState(
                if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                _playerStateFlow.value.currentPosition,
                player.playbackParameters.speed
            )
            .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SEEK_TO or PlaybackStateCompat.ACTION_STOP)
            .build()
        mediaSession.setPlaybackState(playbackState)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(_playerStateFlow.value.fileName.ifEmpty { "Аудиоплеер" })
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setLargeIcon(placeholderBitmap)
            .setContentIntent(mediaSession.controller.sessionActivity)
            .setDeleteIntent(createPendingIntent(ACTION_STOP))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(prevAction).addAction(playPauseAction).addAction(nextAction)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle().setMediaSession(mediaSession.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .setOngoing(isPlaying)
            .build()

        if (_playerStateFlow.value.loadedChapterId.isNotEmpty()) {
            startForeground(NOTIFICATION_ID, notification)
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun createPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, MediaPlayerService::class.java).setAction(action)
        return PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Audio", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
    override fun onDestroy() {
        super.onDestroy()
        triggerSave(_playerStateFlow.value.currentPosition, false, true)
        serviceScope.cancel()
        player.release()
        ambientPlayer.release()
        mediaSession.release()
    }
}
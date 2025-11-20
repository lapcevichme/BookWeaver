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
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
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
import java.net.URL
import javax.inject.Inject

@AndroidEntryPoint
class MediaPlayerService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    @Inject
    lateinit var getChapterPlaybackDataUseCase: GetChapterPlaybackDataUseCase

    @Inject
    lateinit var getAmbientTrackPathUseCase: GetAmbientTrackPathUseCase

    @Inject
    lateinit var saveListenProgressUseCase: SaveListenProgressUseCase

    @Inject
    lateinit var getPlaybackSpeedUseCase: GetPlaybackSpeedUseCase

    @Inject
    lateinit var getAmbientVolumeUseCase: GetAmbientVolumeUseCase

    @Inject
    lateinit var serverRepository: ServerRepository

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
    private val SAVE_THROTTLE_MS = 10_000L
    private val SAVE_DEBOUNCE_MS = 1_000L

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
                    if (totalChapterDurationMs == 0L && currentPlaybackData.size == 1) {
                        totalChapterDurationMs = player.duration
                        Log.d(TAG, "Duration updated from single file source: $totalChapterDurationMs ms")
                        updatePlayerState()
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
                currentMediaItemIndex = player.currentMediaItemIndex
                basePositionOffsetMs = currentPlaybackData.getOrNull(currentMediaItemIndex)?.startMs ?: 0L

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
        currentAudioDirectoryPath = ""

        serviceScope.launch {
            getChapterPlaybackDataUseCase(bookId, chapterId).fold(
                onSuccess = { (playbackData, audioDirectoryPath) ->
                    if (playbackData.isEmpty()) {
                        _playerStateFlow.value = PlayerState(error = "Нет данных для воспроизведения")
                        return@fold
                    }

                    currentPlaybackData = playbackData
                    currentAudioDirectoryPath = audioDirectoryPath
                    // Если сервер прислал 0, то totalChapterDurationMs останется 0
                    totalChapterDurationMs = playbackData.lastOrNull()?.endMs ?: 0L

                    val isStreaming = audioDirectoryPath.startsWith("http")
                    val cleanBaseUrl = if (isStreaming) audioDirectoryPath.removeSuffix("/") else audioDirectoryPath

                    val connection = serverRepository.getCurrentConnection()
                    val headers = mutableMapOf<String, String>()
                    if (connection != null) {
                        headers["Authorization"] = "Bearer ${connection.token}"
                    }

                    val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                        .setUserAgent("BookWeaver-Android")
                        .setDefaultRequestProperties(headers)
                    val mediaSourceFactory = DefaultMediaSourceFactory(httpDataSourceFactory)
                    val mediaSources = mutableListOf<MediaSource>()

                    for (entry in playbackData) {
                        if (entry.audioFile.isBlank()) continue

                        val mediaItem: MediaItem = if (isStreaming) {
                            MediaItem.fromUri("$cleanBaseUrl/${entry.audioFile}")
                        } else {
                            val file = File(audioDirectoryPath, entry.audioFile)
                            if (!file.exists()) continue
                            MediaItem.fromUri(file.toUri())
                        }
                        mediaSources.add(mediaSourceFactory.createMediaSource(mediaItem))
                    }

                    if (mediaSources.isEmpty()) {
                        stopAndClear()
                        _playerStateFlow.value = PlayerState(error = "Аудиофайлы не найдены.")
                        return@fold
                    }

                    player.setMediaSources(mediaSources)

                    if (seekToPositionMs != null) {
                        seekTo(seekToPositionMs)
                    } else {
                        checkAmbient()
                    }

                    player.playWhenReady = playWhenReady
                    player.prepare()

                    // --- ФИКС 1: УТЕЧКА СОКЕТОВ ПРИ ЗАГРУЗКЕ КАРТИНКИ ---
                    var loadedBitmap: Bitmap? = null
                    if (coverPath != null) {
                        if (coverPath.startsWith("http") || coverPath.startsWith("/")) {
                            val fullUrl = if (coverPath.startsWith("/")) {
                                val host = connection?.host ?: ""
                                "$host$coverPath"
                            } else {
                                coverPath
                            }

                            withContext(Dispatchers.IO) {
                                try {
                                    // Используем use, чтобы InputStream и Connection точно закрылись!
                                    val url = URL(fullUrl)
                                    val urlConnection = url.openConnection()

                                    if (connection?.token != null) {
                                        urlConnection.setRequestProperty("Authorization", "Bearer ${connection.token}")
                                    }
                                    
                                    // Добавляем таймауты, чтобы не вешать поток
                                    urlConnection.connectTimeout = 5000
                                    urlConnection.readTimeout = 5000

                                    urlConnection.getInputStream().use { stream ->
                                        loadedBitmap = BitmapFactory.decodeStream(stream)
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to load cover from URL: $fullUrl", e)
                                }
                            }
                        } else {
                            try {
                                loadedBitmap = BitmapFactory.decodeFile(coverPath)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to load cover file", e)
                            }
                        }
                        placeholderBitmap = loadedBitmap
                    }
                    // -----------------------------------------------------

                    _playerStateFlow.value = PlayerState(
                        fileName = chapterTitle,
                        albumArt = loadedBitmap,
                        subtitlesEnabled = true,
                        duration = totalChapterDurationMs,
                        currentPosition = seekToPositionMs ?: 0L,
                        loadedChapterId = chapterId
                    )
                    updateNotification()
                },
                onFailure = { e ->
                    Log.e(TAG, "Error setting media", e)
                    stopAndClear()
                    _playerStateFlow.value = PlayerState(error = "Ошибка загрузки аудио.")
                }
            )
        }
    }

    fun play() { player.play(); if (ambientPlayer.mediaItemCount > 0) ambientPlayer.play() }
    fun pause() { player.pause(); ambientPlayer.pause() }
    fun togglePlayPause() { if (player.isPlaying) pause() else play() }

    fun seekTo(position: Long) {
        if (currentPlaybackData.isEmpty()) return

        // Если длительность 0, разрешаем мотать до "бесконечности",
        // плеер сам найдет ближайший сегмент.
        val safeDuration = if (totalChapterDurationMs > 0) totalChapterDurationMs else Long.MAX_VALUE
        val clampedPosition = position.coerceIn(0L, safeDuration)

        val targetItemIndex = currentPlaybackData.indexOfLast { clampedPosition >= it.startMs }
            .coerceAtLeast(0)

        val targetItem = currentPlaybackData.getOrNull(targetItemIndex) ?: currentPlaybackData.first()
        val relativePosition = (clampedPosition - targetItem.startMs).coerceAtLeast(0L)

        basePositionOffsetMs = targetItem.startMs
        currentMediaItemIndex = targetItemIndex

        player.seekTo(targetItemIndex, relativePosition)
        checkAmbient()

        if (player.playbackState != Player.STATE_IDLE) {
            updatePlayerState()
            triggerSave(position = clampedPosition, isDebounce = true, isFinalSave = false)
        }
    }

    private fun checkAmbient() {
        val entry = currentPlaybackData.getOrNull(currentMediaItemIndex) ?: return
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

        if (ambientName == "none" || currentBookId == null) return

        serviceScope.launch {
            getAmbientTrackPathUseCase(currentBookId!!, ambientName).fold(
                onSuccess = { path ->
                    if (path == null) return@fold
                    try {
                        val connection = serverRepository.getCurrentConnection()
                        val headers = mutableMapOf<String, String>()
                        if (connection != null) {
                            headers["Authorization"] = "Bearer ${connection.token}"
                        }
                        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                            .setUserAgent("BookWeaver-Android")
                            .setDefaultRequestProperties(headers)
                        val mediaSourceFactory = DefaultMediaSourceFactory(httpDataSourceFactory)

                        val mediaSource: MediaSource = if (path.startsWith("http")) {
                            mediaSourceFactory.createMediaSource(MediaItem.fromUri(path))
                        } else {
                            mediaSourceFactory.createMediaSource(MediaItem.fromUri(File(path).toUri()))
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

    fun stopAndClear() {
        triggerSave(position = _playerStateFlow.value.currentPosition, isDebounce = false, isFinalSave = true)
        _playerStateFlow.value = PlayerState()
        player.pause(); player.stop(); player.clearMediaItems()
        ambientPlayer.pause(); ambientPlayer.stop(); ambientPlayer.clearMediaItems()
        currentBookId = null; currentChapterId = null
        currentPlaybackData = emptyList()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun updatePlayerState() {
        // Если мы в состоянии IDLE и длительность 0, лучше ничего не трогать, чтобы не сломать UI
        if (player.playbackState == Player.STATE_IDLE && totalChapterDurationMs == 0L) return

        val currentItemOffset = currentPlaybackData.getOrNull(player.currentMediaItemIndex)?.startMs ?: 0L
        basePositionOffsetMs = currentItemOffset
        val absolutePosition = basePositionOffsetMs + player.currentPosition

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
            // Если тайминги 0, мы показываем duration 0 (indeterminate), но НЕ 19 сек.
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
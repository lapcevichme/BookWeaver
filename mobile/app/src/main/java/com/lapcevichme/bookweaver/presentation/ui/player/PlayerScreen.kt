package com.lapcevichme.bookweaver.presentation.ui.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.text.method.ScrollingMovementMethod
import android.widget.TextView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var mediaService by remember { mutableStateOf<MediaPlayerService?>(null) }
    var isServiceBound by remember { mutableStateOf(false) }

    val serviceConnection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as MediaPlayerService.LocalBinder
                mediaService = binder.getService()
                isServiceBound = true
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                mediaService = null
                isServiceBound = false
            }
        }
    }

    DisposableEffect(Unit) {
        val serviceIntent = Intent(context, MediaPlayerService::class.java)
        ContextCompat.startForegroundService(context, serviceIntent)
        context.bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        onDispose {
            if (isServiceBound) {
                context.unbindService(serviceConnection)
            }
        }
    }

    val playerState by mediaService?.playerStateFlow?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf(PlayerState()) }

    LaunchedEffect(uiState.chapterInfo, mediaService) {
        val chapterInfo = uiState.chapterInfo
        val service = mediaService

        if (chapterInfo != null && service != null) {
            service.setMedia(chapterInfo.media, chapterInfo.chapterTitle)
        }
    }

    when {
        uiState.isLoading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        uiState.error != null -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Ошибка: ${uiState.error}",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        else -> {
            AudioPlayerScreenUI(
                playerState = playerState,
                chapterTitle = uiState.chapterInfo?.chapterTitle ?: "Аудиоплеер",
                mediaPlayerService = mediaService
            )
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioPlayerScreenUI(
    playerState: PlayerState,
    chapterTitle: String,
    mediaPlayerService: MediaPlayerService?
) {
    val coroutineScope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()
    var showSpeedSheet by remember { mutableStateOf(false) }

    fun formatTime(millis: Long): String {
        if (millis < 0) return "00:00"
        val totalSeconds = (millis / 1000).toInt()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        chapterTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { /* TODO: Handle back */ }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = { /* TODO: Handle more options */ }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Еще")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            // Блок обложки
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                if (playerState.albumArt != null) {
                    Image(
                        bitmap = playerState.albumArt!!.asImageBitmap(),
                        contentDescription = "Обложка альбома",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Headphones,
                        contentDescription = "Иконка плеера",
                        modifier = Modifier.size(100.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Блок субтитров
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .padding(vertical = 8.dp)
                    .verticalScroll(rememberScrollState()),
                contentAlignment = Alignment.Center
            ) {
                if (playerState.currentSubtitle.isNotEmpty()) {
                    val subtitleColor = MaterialTheme.colorScheme.onBackground
                    val subtitleColorInt = subtitleColor.toArgb()

                    AndroidView(
                        factory = { ctx ->
                            TextView(ctx).apply {
                                textSize = 18f
                                textAlignment = TextView.TEXT_ALIGNMENT_CENTER
                                setTextColor(subtitleColorInt)
                                setShadowLayer(8f, 0f, 0f, android.graphics.Color.BLACK)
                                movementMethod = ScrollingMovementMethod.getInstance()
                            }
                        },
                        update = { textView ->
                            textView.text = playerState.currentSubtitle
                            textView.setTextColor(subtitleColorInt)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Блок кнопок управления
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                IconButton(onClick = {
                    mediaPlayerService?.seekTo(
                        (playerState.currentPosition - 10000).coerceAtLeast(
                            0L
                        )
                    )
                }) {
                    Icon(
                        Icons.Default.Replay10,
                        contentDescription = "Назад на 10 сек",
                        modifier = Modifier.size(32.dp)
                    )
                }
                IconButton(
                    onClick = { mediaPlayerService?.togglePlayPause() },
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                ) {
                    Icon(
                        modifier = Modifier.size(48.dp),
                        imageVector = if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playerState.isPlaying) "Пауза" else "Воспроизвести",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
                IconButton(onClick = {
                    mediaPlayerService?.seekTo(
                        (playerState.currentPosition + 10000).coerceAtMost(
                            playerState.duration
                        )
                    )
                }) {
                    Icon(
                        Icons.Default.Forward10,
                        contentDescription = "Вперед на 10 сек",
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Нижний ряд кнопок
            Row(
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                IconButton(onClick = { mediaPlayerService?.toggleSubtitles(!playerState.subtitlesEnabled) }) {
                    Icon(
                        Icons.Default.ClosedCaption,
                        contentDescription = "Субтитры",
                        tint = if (playerState.subtitlesEnabled) MaterialTheme.colorScheme.primary else Color.White
                    )
                }

                TextButton(onClick = { showSpeedSheet = true }) {
                    Text(
                        text = "${playerState.playbackSpeed}x",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 16.sp
                    )
                }
                IconButton(onClick = { /* TODO: Settings */ }) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Настройки",
                        tint = Color.White
                    )
                }
            }

            // Блок слайдера
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "${formatTime(playerState.currentPosition)} / ${formatTime(playerState.duration)}",
                    modifier = Modifier.align(Alignment.End),
                    style = MaterialTheme.typography.bodySmall
                )
                Slider(
                    value = playerState.currentPosition.toFloat(),
                    onValueChange = { position -> mediaPlayerService?.seekTo(position.toLong()) },
                    valueRange = 0f..playerState.duration.toFloat().coerceAtLeast(1f),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // Модальное окно скорости
    if (showSpeedSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSpeedSheet = false },
            sheetState = sheetState
        ) {
            val speeds = listOf(0.5f, 0.75f, 1.0f, 1.5f, 2.0f)
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Скорость воспроизведения",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                speeds.forEach { speed ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                mediaPlayerService?.setPlaybackSpeed(speed)
                                coroutineScope
                                    .launch { sheetState.hide() }
                                    .invokeOnCompletion {
                                        if (!sheetState.isVisible) showSpeedSheet = false
                                    }
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = playerState.playbackSpeed == speed,
                            onClick = null
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("${speed}x")
                    }
                }
            }
        }
    }
}


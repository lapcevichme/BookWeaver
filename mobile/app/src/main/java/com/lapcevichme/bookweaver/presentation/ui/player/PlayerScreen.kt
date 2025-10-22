package com.lapcevichme.bookweaver.presentation.ui.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lapcevichme.bookweaver.domain.model.PlayerChapterInfo
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Главный экран плеера, который теперь управляется ViewModel.
 */
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var mediaPlayerService by remember { mutableStateOf<MediaPlayerService?>(null) }
    var serviceBound by remember { mutableStateOf(false) }

    // Состояние плеера из сервиса
    // --- ИСПРАВЛЕНИЕ: Обращаемся к playerStateFlow ---
    val playerState by mediaPlayerService?.playerStateFlow?.collectAsState() ?: remember {
        mutableStateOf(PlayerState())
    }

    // Логика подключения к сервису
    val connection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as MediaPlayerService.LocalBinder
                mediaPlayerService = binder.getService()
                serviceBound = true
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                mediaPlayerService = null
                serviceBound = false
            }
        }
    }

    // Привязка к сервису при входе на экран и отвязка при выходе
    DisposableEffect(context) {
        Intent(context, MediaPlayerService::class.java).also { intent ->
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
        onDispose {
            context.unbindService(connection)
        }
    }

    // Эффект, который реагирует на изменение главы ИЛИ подключение сервиса.
    // Как только оба готовы - запускает плеер.
    LaunchedEffect(uiState.chapterInfo, mediaPlayerService) {
        val chapter = uiState.chapterInfo
        val service = mediaPlayerService

        if (chapter != null && service != null) {
            val audioUri = File(chapter.media.audioPath).toUri()
            val subtitlesUri = chapter.media.subtitlesPath?.let { File(it).toUri() }

            service.setMedia(audioUri, subtitlesUri)
        }
    }

    // UI плеера
    AudioPlayerScreen(
        uiState = uiState,
        playerState = playerState,
        mediaPlayerService = mediaPlayerService
    )
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioPlayerScreen(
    uiState: PlayerUiState,
    playerState: PlayerState,
    mediaPlayerService: MediaPlayerService?
) {
    val coroutineScope = rememberCoroutineScope()
    var showSpeedSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Плеер") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {

            // --- Отображение состояния загрузки/ошибки/плеера ---
            when {
                uiState.isLoading -> {
                    CircularProgressIndicator()
                }
                uiState.error != null -> {
                    Text(
                        text = uiState.error,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                }
                uiState.chapterInfo != null -> {
                    // --- UI Плеера, когда все готово ---
                    PlayerContent(
                        playerState = playerState,
                        chapterInfo = uiState.chapterInfo,
                        onPlayPause = { mediaPlayerService?.togglePlayPause() },
                        onSeek = { position -> mediaPlayerService?.seekTo(position) },
                        onShowSpeedSheet = { showSpeedSheet = true }
                    )
                }
            }
        }

        // --- Нижний лист для выбора скорости ---
        if (showSpeedSheet) {
            ModalBottomSheet(
                onDismissRequest = { showSpeedSheet = false },
                sheetState = sheetState
            ) {
                val speeds = listOf(0.5f, 0.75f, 1.0f, 1.5f, 2.0f)
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "Скорость воспроизведения", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 16.dp))
                    speeds.forEach { speed ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    mediaPlayerService?.setPlaybackSpeed(speed)
                                    coroutineScope
                                        .launch { sheetState.hide() }
                                        .invokeOnCompletion { if (!sheetState.isVisible) showSpeedSheet = false }
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = playerState.playbackSpeed == speed,
                                onClick = null
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(text = "${speed}x")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PlayerContent(
    playerState: PlayerState,
    chapterInfo: PlayerChapterInfo,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onShowSpeedSheet: () -> Unit
) {
    // --- Обложка/плейсхолдер ---
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .padding(horizontal = 32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (playerState.albumArt != null) {
            Image(
                bitmap = playerState.albumArt.asImageBitmap(),
                contentDescription = "Обложка",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Default.Book,
                contentDescription = "Обложка",
                modifier = Modifier.size(100.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    Spacer(modifier = Modifier.height(32.dp))

    // --- Названия ---
    Text(
        text = chapterInfo.chapterTitle,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
    Text(
        text = chapterInfo.bookTitle,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )

    Spacer(modifier = Modifier.height(16.dp))

    // --- Субтитры (если есть) ---
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp) // Фиксированная высота для 1-2 строк текста
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = playerState.currentSubtitle.ifEmpty { " " }.toString(),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
    }

    // --- Слайдер времени ---
    Slider(
        value = playerState.currentPosition.toFloat(),
        onValueChange = { onSeek(it.toLong()) },
        valueRange = 0f..(playerState.duration.toFloat().coerceAtLeast(0f)),
        modifier = Modifier.fillMaxWidth()
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = formatTime(playerState.currentPosition), style = MaterialTheme.typography.bodySmall)
        Text(text = formatTime(playerState.duration), style = MaterialTheme.typography.bodySmall)
    }

    Spacer(modifier = Modifier.height(16.dp))

    // --- Кнопки управления ---
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Кнопка скорости
        IconButton(onClick = onShowSpeedSheet) {
            Icon(Icons.Default.Speed, contentDescription = "Скорость")
        }

        // Кнопка "назад" (пока заглушка)
        IconButton(onClick = { /* TODO */ }) {
            Icon(Icons.Default.SkipPrevious, contentDescription = "Назад", modifier = Modifier.size(40.dp))
        }

        // Кнопка Play/Pause
        IconButton(
            onClick = onPlayPause,
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
        ) {
            Icon(
                imageVector = if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = "Play/Pause",
                modifier = Modifier.size(40.dp)
            )
        }

        // Кнопка "вперед" (пока заглушка)
        IconButton(onClick = { /* TODO */ }) {
            Icon(Icons.Default.SkipNext, contentDescription = "Вперед", modifier = Modifier.size(40.dp))
        }

        // Кнопка "повтор" (пока заглушка)
        IconButton(onClick = { /* TODO */ }) {
            Icon(Icons.Default.Repeat, contentDescription = "Повтор")
        }
    }
}

private fun formatTime(millis: Long): String {
    val formatter = SimpleDateFormat("mm:ss", Locale.getDefault())
    return formatter.format(Date(millis))
}


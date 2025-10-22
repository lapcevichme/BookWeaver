package com.lapcevichme.bookweaver.presentation.ui.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.provider.OpenableColumns
import android.widget.TextView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Button
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen() {
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

    // Привязываемся к сервису, когда Composable входит в композицию,
    // и отвязываемся, когда он уходит.
    DisposableEffect(Unit) {
        val serviceIntent = Intent(context, MediaPlayerService::class.java)
        ContextCompat.startForegroundService(context, serviceIntent)
        context.bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        onDispose {
            if (isServiceBound) {
                context.unbindService(serviceConnection)
                isServiceBound = false
            }
        }
    }

    // Передаем сервис в наш UI-компонент
    AudioPlayerScreen(mediaPlayerService = mediaService)
}

/**
 * Копирует URI (например, из content://) во временный файл в кэше.
 * Это необходимо, чтобы ExoPlayer мог получить к нему доступ.
 */
private fun copyUriToCache(context: Context, uri: Uri, outputFileName: String? = null): Uri? {
    return try {
        val contentResolver = context.contentResolver
        val fileName =
            outputFileName ?: contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                cursor.moveToFirst()
                cursor.getString(nameIndex)
            } ?: "temp_file"

        val outputFile = File(context.cacheDir, fileName)
        contentResolver.openInputStream(uri)?.use { inputStream ->
            FileOutputStream(outputFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }
        Uri.fromFile(outputFile)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

/**
 * UI-компонент плеера. Скопирован из твоей MainActivity.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioPlayerScreen(mediaPlayerService: MediaPlayerService?) {
    val playerState by mediaPlayerService?.playerStateFlow?.collectAsStateWithLifecycle()
        ?: remember {
            mutableStateOf(PlayerState())
        }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var currentCachedAudioUri by remember { mutableStateOf<Uri?>(null) }

    val sheetState = rememberModalBottomSheetState()
    var showSpeedSheet by remember { mutableStateOf(false) }

    val audioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { contentUri ->
            copyUriToCache(context, contentUri)?.let { fileUri ->
                currentCachedAudioUri = fileUri
                mediaPlayerService?.playFile(fileUri, context)
            }
        }
    }

    val subtitleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        val audioUri = currentCachedAudioUri
        if (uri == null || audioUri == null) {
            Toast.makeText(context, "Сначала выберите аудиофайл", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }

        val targetSrtName = File(audioUri.path!!).nameWithoutExtension + ".srt"

        copyUriToCache(context, uri, targetSrtName)?.let {
            Toast.makeText(context, "Субтитры загружены", Toast.LENGTH_SHORT).show()
            mediaPlayerService?.reloadWithSubtitles()
        }
    }


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
                        playerState.fileName.ifEmpty { "Аудиоплеер" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    // TODO: В будущем здесь будет навигация назад или "вниз"
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
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.weight(1f))

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

            Spacer(modifier = Modifier.weight(1f))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                if (playerState.subtitlesEnabled && playerState.currentSubtitle.isNotEmpty()) {
                    AndroidView(
                        factory = { ctx ->
                            TextView(ctx).apply {
                                textSize = 18f
                                textAlignment = TextView.TEXT_ALIGNMENT_CENTER
                                setTextColor(Color.White.hashCode())
                                setShadowLayer(8f, 0f, 0f, android.graphics.Color.BLACK)
                            }
                        },
                        update = { textView ->
                            textView.text = playerState.currentSubtitle
                        }
                    )
                }
            }

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
                    onClick = { if (playerState.isPlaying) mediaPlayerService?.pause() else mediaPlayerService?.play() },
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

            // --- Кнопки для выбора файлов (оставляем их для отладки) ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { audioLauncher.launch("audio/*") },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Выбрать аудио")
                }
                Button(
                    onClick = { subtitleLauncher.launch("*/*") },
                    modifier = Modifier.weight(1f),
                    enabled = currentCachedAudioUri != null
                ) {
                    Text("Загрузить SRT")
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

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

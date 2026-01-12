package com.lapcevichme.bookweaver.core.navigation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.lapcevichme.bookweaver.core.service.MediaPlayerService
import com.lapcevichme.bookweaver.features.player.PlayerUiState
import com.lapcevichme.bookweaver.features.player.PlayerViewModel

@Composable
fun rememberMediaPlayerService(): MediaPlayerService? {
    val context = LocalContext.current
    var mediaService by remember { mutableStateOf<MediaPlayerService?>(null) }

    val serviceConnection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as MediaPlayerService.LocalBinder
                mediaService = binder.getService()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                mediaService = null
            }
        }
    }

    DisposableEffect(context) {
        val serviceIntent = Intent(context, MediaPlayerService::class.java)
        ContextCompat.startForegroundService(context, serviceIntent)
        context.bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        onDispose {
            Log.d("AppNavHost", "onDispose: Unbinding from MediaPlayerService")
            context.unbindService(serviceConnection)
        }
    }

    return mediaService
}

@Composable
fun MediaPlayerSyncEffect(
    playerViewModel: PlayerViewModel,
    mediaService: MediaPlayerService?,
    playerUiState: PlayerUiState
) {
    LaunchedEffect(playerUiState, mediaService) {
        val service = mediaService ?: return@LaunchedEffect
        val command = playerUiState.loadCommand
        val chapterInfo = playerUiState.chapterInfo
        val bookId = playerUiState.bookId
        val chapterId = playerUiState.chapterId

        val currentServiceChapterId = service.playerStateFlow.value.loadedChapterId
        val isServiceEmpty = currentServiceChapterId.isEmpty()

        // Сценарий 0: Очистка
        if (playerUiState.clearService) {
            Log.d("AppNavHost_Sync", "SCENARIO 0: ClearService command received.")
            if (!isServiceEmpty) {
                service.stopAndClear()
            }
            playerViewModel.onServiceCleared()
            return@LaunchedEffect
        }

        // Сценарий 1: Активная команда
        if (command != null) {
            Log.d("AppNavHost_Sync", "SCENARIO 1: Active LoadCommand: Play=${command.playWhenReady}, Seek=${command.seekToPositionMs}")
            if (chapterInfo == null || bookId == null || chapterId == null) {
                Log.e("AppNavHost_Sync", "LoadCommand failed: chapterInfo or IDs are null")
                return@LaunchedEffect
            }

            val isCorrectChapterLoaded = currentServiceChapterId.isNotEmpty() &&
                    currentServiceChapterId == chapterId

            if (isCorrectChapterLoaded) {
                Log.d("AppNavHost_Sync", "Executing command on loaded chapter")
                if (command.seekToPositionMs != null) {
                    service.seekTo(command.seekToPositionMs)
                }
                if (command.playWhenReady) {
                    service.play()
                }
            } else {
                Log.d("AppNavHost_Sync", "Executing command by calling setMedia")
                service.setMedia(
                    bookId = bookId,
                    chapterId = chapterId,
                    chapterTitle = chapterInfo.chapterTitle,
                    coverPath = chapterInfo.coverPath,
                    playWhenReady = command.playWhenReady,
                    seekToPositionMs = command.seekToPositionMs ?: chapterInfo.lastListenedPosition
                )
            }
            return@LaunchedEffect
        }

        // Сценарий 2: Пассивное восстановление
        if (playerUiState.isLoading) {
            Log.d("AppNavHost_Sync", "SCENARIO 2: SKIPPED (ViewModel is loading).")
            return@LaunchedEffect
        }

        if (chapterInfo != null && bookId != null && chapterId != null) {
            val isCorrectChapterLoaded = currentServiceChapterId.isNotEmpty() && currentServiceChapterId == chapterId

            if (!isCorrectChapterLoaded && isServiceEmpty) {
                Log.d("AppNavHost_Sync", "Triggering passive restore (Service was empty).")
                service.setMedia(
                    bookId = bookId,
                    chapterId = chapterId,
                    chapterTitle = chapterInfo.chapterTitle,
                    coverPath = chapterInfo.coverPath,
                    playWhenReady = false,
                    seekToPositionMs = chapterInfo.lastListenedPosition
                )
            } else if (!isCorrectChapterLoaded && !isServiceEmpty) {
                Log.d("AppNavHost_Sync", "Triggering passive restore (Service has wrong chapter).")
                service.setMedia(
                    bookId = bookId,
                    chapterId = chapterId,
                    chapterTitle = chapterInfo.chapterTitle,
                    coverPath = chapterInfo.coverPath,
                    playWhenReady = false,
                    seekToPositionMs = chapterInfo.lastListenedPosition
                )
            }
        }
    }
}
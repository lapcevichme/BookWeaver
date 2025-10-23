package com.lapcevichme.bookweaver.presentation.ui.chapterdetails

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lapcevichme.bookweaver.presentation.ui.main.MainViewModel
import com.lapcevichme.bookweaver.presentation.ui.player.MediaPlayerService
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChapterDetailsScreen(
    state: ChapterDetailsUiState,
    onNavigateBack: () -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()
    val tabTitles = listOf("Сводка", "Сценарий", "Оригинал")

    val context = LocalContext.current
    val activity = remember(context) {
        context as? ComponentActivity
            ?: throw IllegalStateException("Context is not a ComponentActivity")
    }
    val mainViewModel: MainViewModel = hiltViewModel(activity)


    // Логика привязки к MediaPlayerService
    var mediaService by remember { mutableStateOf<MediaPlayerService?>(null) }
    val isServiceBound = mediaService != null

    val playerState by mediaService?.playerStateFlow
        ?.map { it.currentPosition }
        ?.collectAsStateWithLifecycle(initialValue = 0L)
        ?: remember { mutableLongStateOf(0L) }

    val serviceConnection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                Log.d("ChapterDetailsScreen", "Service connected")
                val binder = service as MediaPlayerService.LocalBinder
                mediaService = binder.getService()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Log.d("ChapterDetailsScreen", "Service disconnected")
                mediaService = null
            }
        }
    }

    DisposableEffect(Unit) {
        val serviceIntent = Intent(context, MediaPlayerService::class.java)
        context.bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        onDispose {
            if (isServiceBound) {
                context.unbindService(serviceConnection)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.chapterTitle, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            TabRow(selectedTabIndex = pagerState.currentPage) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        text = { Text(title) }
                    )
                }
            }

            when {
                state.isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                state.error != null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Ошибка: ${state.error}")
                    }
                }

                else -> {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize()
                    ) { page ->
                        when (page) {
                            0 -> SummaryContent(
                                state.details?.teaser ?: "",
                                state.details?.synopsis ?: ""
                            )

                            1 -> ScenarioContent(
                                scenario = state.details?.scenario ?: emptyList(),
                                currentPosition = playerState,
                                onEntryClick = { entry ->
                                    mediaService?.seekTo(entry.startMs)
                                    mainViewModel.navigateToPlayerTab()
                                }
                            )

                            2 -> OriginalTextContent(state.details?.originalText ?: "")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryContent(teaser: String, synopsis: String) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Тизер", style = MaterialTheme.typography.titleLarge)
        Text(teaser, style = MaterialTheme.typography.bodyLarge)
        HorizontalDivider()
        Text("Синопсис", style = MaterialTheme.typography.titleLarge)
        Text(synopsis, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ScenarioContent(
    scenario: List<UiScenarioEntry>,
    currentPosition: Long,
    onEntryClick: (UiScenarioEntry) -> Unit
) {
    val lazyListState = rememberLazyListState()

    val currentPlayingEntryId by remember(currentPosition) {
        derivedStateOf {
            scenario.firstOrNull {
                currentPosition >= it.startMs && currentPosition <= it.endMs
            }?.id
        }
    }

    LaunchedEffect(currentPlayingEntryId) {
        if (currentPlayingEntryId != null) {
            val index = scenario.indexOfFirst { it.id == currentPlayingEntryId }
            if (index != -1) {
                // TODO: Добавить проверку, виден ли элемент, чтобы не скроллить, если пользователь читает
                lazyListState.animateScrollToItem(index, scrollOffset = -100)
            }
        }
    }

    SelectionContainer {
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(scenario, key = { _, entry -> entry.id }) { index, entry ->
                val isPlaying =
                    currentPosition >= entry.startMs && currentPosition <= entry.endMs

                val highlightColor = MaterialTheme.colorScheme.primary
                val speakerColor = MaterialTheme.colorScheme.onBackground
                val defaultTextColor =
                    MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                val playingTextColor = MaterialTheme.colorScheme.onBackground

                val annotatedString = buildAnnotatedString {
                    withStyle(
                        style = SpanStyle(
                            fontWeight = FontWeight.Bold,
                            color = if (isPlaying) highlightColor else speakerColor
                        )
                    ) {
                        append(entry.speaker)
                        append(": ")
                    }

                    if (entry.words.isEmpty()) {
                        // Fallback, если 'words' нет
                        withStyle(style = SpanStyle(color = if (isPlaying) playingTextColor else defaultTextColor)) {
                            append(entry.text)
                        }
                    } else {
                        // Логика "Караоке"
                        entry.words.forEach { word ->
                            val isCurrentWord = isPlaying &&
                                    currentPosition >= word.start && currentPosition <= word.end

                            withStyle(
                                style = SpanStyle(
                                    color = when {
                                        isCurrentWord -> highlightColor
                                        isPlaying -> playingTextColor
                                        else -> defaultTextColor
                                    },
                                    fontWeight = if (isCurrentWord) FontWeight.Bold else FontWeight.Normal
                                )
                            ) {
                                append(word.word)
                            }
                        }
                    }
                }

                Text(
                    text = annotatedString,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isPlaying) MaterialTheme.colorScheme.surfaceVariant
                            else MaterialTheme.colorScheme.surface,
                            shape = MaterialTheme.shapes.medium
                        )
                        .clickable { onEntryClick(entry) }
                        .padding(8.dp),
                    style = MaterialTheme.typography.bodyLarge
                )

                if (index < scenario.lastIndex) {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun OriginalTextContent(text: String) {
    SelectionContainer {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}


package com.lapcevichme.bookweaver.features.chapterdetails

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import java.io.File
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil.compose.AsyncImage
import com.lapcevichme.bookweaver.core.PlayerState
import com.lapcevichme.bookweaver.features.main.MainViewModel
import com.lapcevichme.bookweaver.features.player.PlayerViewModel
import dev.jeziellago.compose.markdowntext.MarkdownText
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class,
    ExperimentalMaterial3ExpressiveApi::class
)
@Composable
fun ChapterDetailsScreen(
    state: ChapterDetailsUiState,
    playerState: PlayerState,
    playerViewModel: PlayerViewModel,
    mainViewModel: MainViewModel,
    onNavigateBack: () -> Unit,
    viewModel: ChapterDetailsViewModel = hiltViewModel()
) {
    val pagerState = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()
    val tabTitles = listOf("Сводка", "Сценарий", "Оригинал")

    val context = LocalContext.current

    var copiedText by remember { mutableStateOf<String?>(null) }
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    DisposableEffect(clipboardManager) {
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            try {
                if (clipboardManager.hasPrimaryClip() &&
                    clipboardManager.primaryClipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true
                ) {
                    val text =
                        clipboardManager.primaryClip?.getItemAt(0)?.text?.toString()
                    if (!text.isNullOrEmpty()) {
                        Log.d("ChapterDetailsScreen", "Text copied: $text")
                        copiedText = text
                    }
                }
            } catch (e: Exception) {
                Log.e("ChapterDetailsScreen", "Error reading clipboard", e)
            }
        }
        clipboardManager.addPrimaryClipChangedListener(listener)
        onDispose {
            clipboardManager.removePrimaryClipChangedListener(listener)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.chapterTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {

            Column(modifier = Modifier.padding(padding)) {
                PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
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
                            LoadingIndicator()
                        }
                    }

                    state.error != null -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Ошибка: ${state.error}")
                        }
                    }

                    state.details != null -> {
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize()
                        ) { page ->
                            when (page) {
                                0 -> SummaryContent(
                                    state.details.teaser,
                                    state.details.synopsis
                                )

                                1 -> {
                                    val isThisChapterPlaying =
                                        (state.details.hasAudio == true) && (state.chapterId == playerState.loadedChapterId)

                                    val positionForThisChapter = if (isThisChapterPlaying) {
                                        playerState.currentPosition
                                    } else {
                                        -1L
                                    }

                                    ScenarioContent(
                                        scenario = state.details.scenario,
                                        currentPosition = positionForThisChapter,
                                        basePath = state.details.basePath,
                                        onEntryClick = { entry ->

                                            if (!entry.isPlayable) return@ScenarioContent

                                            if (isThisChapterPlaying) {
                                                playerViewModel.seekToAndPlay(entry.startMs)

                                            } else {
                                                playerViewModel.playChapter(
                                                    bookId = state.bookId,
                                                    chapterId = state.chapterId,
                                                    seekToPositionMs = entry.startMs
                                                )
                                            }
                                        }
                                    )
                                }

                                2 -> OriginalTextContent(state.details.originalText, state.details.basePath)
                            }
                        }
                    }
                    else -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Нет данных для отображения")
                        }
                    }
                }
            }

            CopiedTextActionsCard(
                modifier = Modifier.align(Alignment.BottomCenter),
                copiedText = copiedText ?: "",
                onDismiss = { copiedText = null },
                onSaveQuote = { text ->
                    Log.d("ChapterDetailsScreen", "СОХРАНИТЬ ЦИТАТУ: $text")
                    copiedText = null
                },
                onSaveNote = { text ->
                    Log.d("ChapterDetailsScreen", "СОХРАНИТЬ ЗАМЕТКУ: $text")
                    copiedText = null
                }
            )
        }
    }
}


@Composable
private fun CopiedTextActionsCard(
    modifier: Modifier = Modifier,
    copiedText: String,
    onDismiss: () -> Unit,
    onSaveQuote: (String) -> Unit,
    onSaveNote: (String) -> Unit
) {
    AnimatedVisibility(
        visible = copiedText.isNotEmpty(),
        modifier = modifier,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it })
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .navigationBarsPadding(),
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Скопированный текст:",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = copiedText,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Закрыть")
                    }
                    TextButton(onClick = {
                        onSaveNote(copiedText)
                    }) {
                        Text("Заметка")
                    }
                    TextButton(onClick = {
                        onSaveQuote(copiedText)
                    }) {
                        Text("Цитата")
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
    basePath: String?,
    onEntryClick: (UiScenarioEntry) -> Unit
) {
    val lazyListState = rememberLazyListState()

    val currentPlayingEntryId by remember(currentPosition, scenario) {
        derivedStateOf {
            if (scenario.none { it.isPlayable }) return@derivedStateOf null

            scenario.firstOrNull { entry ->
                val isLastEntry = scenario.lastOrNull()?.id == entry.id
                if (isLastEntry) {
                    currentPosition >= entry.startMs && currentPosition <= entry.endMs
                } else {
                    currentPosition >= entry.startMs && currentPosition < entry.endMs
                }
            }?.id
        }
    }

    LaunchedEffect(currentPlayingEntryId) {
        if (currentPlayingEntryId == null) return@LaunchedEffect

        val targetIndex = scenario.indexOfFirst { it.id == currentPlayingEntryId }
        if (targetIndex == -1) return@LaunchedEffect

        val isScrolling = lazyListState.isScrollInProgress
        if (isScrolling) return@LaunchedEffect

        val visibleItemsInfo = lazyListState.layoutInfo.visibleItemsInfo
        if (visibleItemsInfo.isEmpty()) return@LaunchedEffect

        val isTargetVisible = visibleItemsInfo.any { it.index == targetIndex }

        if (isTargetVisible) {
            lazyListState.animateScrollToItem(targetIndex, scrollOffset = -100)
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

                val isPlaying = entry.isPlayable && (if (index == scenario.lastIndex) {
                    currentPosition >= entry.startMs && currentPosition <= entry.endMs
                } else {
                    currentPosition >= entry.startMs && currentPosition < entry.endMs
                })

                val highlightColor = MaterialTheme.colorScheme.primary
                val speakerColor = MaterialTheme.colorScheme.primary
                val defaultTextColor = MaterialTheme.colorScheme.onBackground
                val interactiveTextColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                val playingTextColor = MaterialTheme.colorScheme.onBackground
                val baseColor = if (entry.isPlayable) interactiveTextColor else defaultTextColor

                val annotatedString = buildAnnotatedString {
                    if (entry.words.isEmpty()) {
                        withStyle(style = SpanStyle(color = if (isPlaying) playingTextColor else baseColor)) {
                            append(entry.text)
                        }
                    } else {
                        entry.words.forEach { word ->
                            val isCurrentWord = isPlaying &&
                                    currentPosition >= word.start && currentPosition <= word.end

                            withStyle(
                                style = SpanStyle(
                                    color = when {
                                        isCurrentWord -> highlightColor
                                        isPlaying -> playingTextColor
                                        else -> baseColor
                                    },
                                    fontWeight = if (isCurrentWord) FontWeight.Bold else FontWeight.Normal
                                )
                            ) {
                                append(word.word)
                            }
                            append(" ")
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isPlaying) MaterialTheme.colorScheme.surfaceVariant
                            else Color.Transparent,
                            shape = MaterialTheme.shapes.medium
                        )
                        .clip(MaterialTheme.shapes.medium)
                        .clickable(enabled = entry.isPlayable) { onEntryClick(entry) }
                        .padding(8.dp)
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        if (entry.type == "image" && entry.imageSrc != null) {
                            val resolvedPath = remember(entry.imageSrc, basePath) {
                                if (basePath == null || entry.imageSrc.startsWith("http") || entry.imageSrc.startsWith("/")) {
                                    entry.imageSrc
                                } else {
                                    val baseDir = File(basePath).parentFile
                                    if (baseDir != null) {
                                        File(baseDir, entry.imageSrc).canonicalPath
                                    } else {
                                        entry.imageSrc
                                    }
                                }
                            }

                            AsyncImage(
                                model = if (resolvedPath.startsWith("http")) resolvedPath else "file://$resolvedPath",
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                                    .clip(MaterialTheme.shapes.small),
                                contentScale = ContentScale.Crop
                            )
                            Spacer(Modifier.height(8.dp))
                        }

                        val showSpeaker = entry.speaker.isNotBlank() && 
                                entry.speaker != "Narrator" && 
                                entry.speaker != "Рассказчик"

                        if (showSpeaker) {
                            Text(
                                text = entry.speaker,
                                style = MaterialTheme.typography.labelMedium,
                                color = speakerColor,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 2.dp)
                            )
                        }

                        if (entry.text.isNotBlank()) {
                            if (entry.words.isEmpty()) {
                                MarkdownText(
                                    markdown = entry.text,
                                    modifier = Modifier.fillMaxWidth(),
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        color = if (isPlaying) playingTextColor else baseColor
                                    )
                                )
                            } else {
                                Text(
                                    text = annotatedString,
                                    modifier = Modifier.fillMaxWidth(),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                    }
                }

            }
        }
    }
}

@Composable
private fun OriginalTextContent(text: String, basePath: String?) {
    val processedText = remember(text, basePath) {
        val trimmedText = text.trim()
        if (basePath == null) trimmedText
        else {
            val baseDir = File(basePath).parentFile ?: return@remember trimmedText
            val pattern = Regex("""!\[(.*?)\]\((.*?)\)""")
            pattern.replace(trimmedText) { matchResult ->
                val alt = matchResult.groupValues[1]
                val path = matchResult.groupValues[2]
                if (path.startsWith("http") || path.startsWith("/")) {
                    matchResult.value
                } else {
                    val absolutePath = File(baseDir, path).canonicalPath
                    "![$alt](file://$absolutePath)"
                }
            }
        }
    }

    SelectionContainer {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            MarkdownText(
                markdown = processedText,
                style = MaterialTheme.typography.bodyLarge,
                linkColor = MaterialTheme.colorScheme.primary,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}
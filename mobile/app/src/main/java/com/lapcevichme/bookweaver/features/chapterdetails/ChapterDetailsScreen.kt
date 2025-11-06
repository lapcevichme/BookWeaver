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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.lapcevichme.bookweaver.core.PlayerState
import com.lapcevichme.bookweaver.features.main.MainViewModel
import com.lapcevichme.bookweaver.features.player.PlayerViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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

    // Логика буфера обмена
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
                            CircularProgressIndicator()
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
                                        onEntryClick = { entry ->

                                            if (!entry.isPlayable) return@ScenarioContent
                                            
                                            if (isThisChapterPlaying) {
                                                Log.d(
                                                    "ChapterDetailsScreen",
                                                    "onEntryClick: Та же глава. Отправка команды seekAndPlay ${entry.startMs}"
                                                )
                                                // playerViewModel.seekTo(entry.startMs)
                                                //
                                                // if (!playerState.isPlaying) {
                                                //     playerViewModel.play()
                                                // }
                                                playerViewModel.seekToAndPlay(entry.startMs)

                                            } else {
                                                Log.d(
                                                    "ChapterDetailsScreen",
                                                    "onEntryClick: Другая глава. Вызов playChapter(${state.bookId}, ${state.chapterId}, ${entry.startMs})"
                                                )
                                                playerViewModel.playChapter(
                                                    bookId = state.bookId,
                                                    chapterId = state.chapterId,
                                                    seekToPositionMs = entry.startMs
                                                )
                                            }
                                            mainViewModel.navigateToPlayerTab()
                                        }
                                    )
                                }

                                2 -> OriginalTextContent(state.details.originalText)
                            }
                        }
                    }
                    // Добавим 'else' на случай, если state.details == null, но isLoading == false и error == null
                    else -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Нет данных для отображения")
                        }
                    }
                }
            }

            // Карточка буфера обмена
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
    onEntryClick: (UiScenarioEntry) -> Unit
) {
    val lazyListState = rememberLazyListState()

    val currentPlayingEntryId by remember(currentPosition, scenario) {
        derivedStateOf {
            // Не ищем, если нет аудио
            if (scenario.none { it.isPlayable }) return@derivedStateOf null

            scenario.firstOrNull { entry ->
                val isLastEntry = scenario.lastOrNull()?.id == entry.id
                // Логика для последнего элемента: включаем endMs
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

        // Не скроллим, если пользователь сам крутит список
        val isScrolling = lazyListState.isScrollInProgress
        if (isScrolling) {
            Log.d("ScenarioContent", "User is scrolling. No auto-scroll.")
            return@LaunchedEffect
        }

        // Проверяем, виден ли элемент (даже частично)
        val visibleItemsInfo = lazyListState.layoutInfo.visibleItemsInfo
        if (visibleItemsInfo.isEmpty()) {
            return@LaunchedEffect // Список еще не отрисовался
        }
        val isTargetVisible = visibleItemsInfo.any { it.index == targetIndex }

        // Скроллим, ТОЛЬКО ЕСЛИ элемент уже виден
        if (isTargetVisible) {
            Log.d("ScenarioContent", "Item $targetIndex is visible. Scrolling to focus.")
            // Добавляем -100 смещения, чтобы реплика была не у самого края
            lazyListState.animateScrollToItem(targetIndex, scrollOffset = -100)
        } else {
            Log.d("ScenarioContent", "Item $targetIndex is NOT visible. No auto-scroll.")
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
                val speakerColor = MaterialTheme.colorScheme.onBackground
                // Текст по умолчанию (для режима "нет аудио")
                val defaultTextColor = MaterialTheme.colorScheme.onBackground
                // Текст, с которым можно взаимодействовать (есть аудио, но не играет)
                val interactiveTextColor =
                    MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                // Текст, который играет сейчас
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

                    // Базовый цвет текста зависит от того, есть ли аудио
                    val baseColor = if (entry.isPlayable) interactiveTextColor else defaultTextColor

                    if (entry.words.isEmpty()) {
                        // Если слов нет, просто вставляем текст
                        withStyle(style = SpanStyle(color = if (isPlaying) playingTextColor else baseColor)) {
                            append(entry.text)
                        }
                    } else {
                        // Если слова есть, строим "караоке"
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
                            append(" ") // Добавляем пробел между словами
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
                        .clickable(enabled = entry.isPlayable) { onEntryClick(entry) }
                        .padding(8.dp),
                    style = MaterialTheme.typography.bodyLarge
                )
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

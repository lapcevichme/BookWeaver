package com.lapcevichme.bookweaver.features.bookhub

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Этот экран теперь является "Хабом" для активной книги.
 * Он не знает о NavController, а только сообщает о событиях навигации через коллбэки.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BookHubScreen(
    uiState: BookDetailsUiState,
    bottomContentPadding: Dp,
    onNavigateToCharacters: () -> Unit,
    onNavigateToSettings: (bookId: String) -> Unit,
    onChapterViewDetailsClick: (chapterId: String) -> Unit,
    onChapterPlayClick: (chapterId: String) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.bookDetails?.title ?: "Загрузка...", maxLines = 1) },
                actions = {
                    // Кнопка для перехода к персонажам
                    IconButton(onClick = onNavigateToCharacters, enabled = uiState.bookId != null) {
                        Icon(Icons.Default.AccountCircle, contentDescription = "Персонажи")
                    }
                    // Кнопка для перехода в настройки книги
                    IconButton(
                        onClick = { uiState.bookId?.let { onNavigateToSettings(it) } },
                        enabled = uiState.bookId != null
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки книги")
                    }
                }
            )
        }
    ) { padding ->
        when {
            uiState.isLoading && uiState.bookDetails == null -> { // Показываем индикатор только при первой загрузке
                Box(Modifier
                    .fillMaxSize()
                    .padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            uiState.error != null -> {
                Box(Modifier
                    .fillMaxSize()
                    .padding(padding), contentAlignment = Alignment.Center) {
                    Text("Ошибка: ${uiState.error}")
                }
            }

            uiState.bookDetails != null -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = bottomContentPadding
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    uiState.bookDetails.volumes.forEach { volume ->
                        stickyHeader {
                            Surface(
                                modifier = Modifier.fillParentMaxWidth(),
                                shadowElevation = 2.dp
                            ) {
                                Text(
                                    text = volume.title,
                                    style = MaterialTheme.typography.titleLarge,
                                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
                                )
                            }
                        }
                        items(volume.chapters, key = { it.id }) { chapter ->
                            ChapterItem(
                                chapter = chapter,
                                isActive = chapter.id == uiState.activeChapterId,
                                onViewDetailsClick = { onChapterViewDetailsClick(chapter.id) },
                                onPlayClick = { onChapterPlayClick(chapter.id) }
                            )
                        }
                    }
                }
            }
            // Случай, когда книга не выбрана (bookId == null), но ошибки нет
            else -> {
                Box(Modifier
                    .fillMaxSize()
                    .padding(padding), contentAlignment = Alignment.Center) {
                    Text("Активная книга не выбрана")
                }
            }
        }
    }
}

@Composable
private fun ChapterItem(
    chapter: UiChapter,
    isActive: Boolean,
    onViewDetailsClick: () -> Unit,
    onPlayClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onViewDetailsClick),
        // Меняем цвет фона для активной главы
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = chapter.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else LocalContentColor.current
            )

            Spacer(modifier = Modifier.width(8.dp))

            if (isActive) {
                // Показываем иконку, если глава активна
                Icon(
                    imageVector = Icons.Default.GraphicEq,
                    contentDescription = "Сейчас играет",
                    tint = MaterialTheme.colorScheme.primary
                )
            } else {
                // Показываем кнопку "Слушать", если глава не активна
                IconButton(onClick = onPlayClick, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = Icons.Default.Headset,
                        contentDescription = "Слушать главу",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

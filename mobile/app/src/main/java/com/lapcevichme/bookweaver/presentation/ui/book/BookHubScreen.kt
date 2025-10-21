package com.lapcevichme.bookweaver.presentation.ui.book

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lapcevichme.bookweaver.presentation.ui.book.bookdetails.BookDetailsUiState
import com.lapcevichme.bookweaver.presentation.ui.book.bookdetails.UiChapter

/**
 * Этот экран теперь является "Хабом" для активной книги.
 * Он не знает о NavController, а только сообщает о событиях навигации через коллбэки.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BookHubScreen(
    uiState: BookDetailsUiState,
    onNavigateToCharacters: () -> Unit,
    onNavigateToSettings: (bookId: String) -> Unit,
    onChapterClick: (chapterId: String) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.bookDetails?.title ?: "Загрузка...", maxLines = 1) },
                actions = {
                    // Кнопка для перехода к персонажам
                    IconButton(onClick = onNavigateToCharacters) {
                        Icon(Icons.Default.AccountCircle, contentDescription = "Персонажи")
                    }
                    // Кнопка для перехода в настройки книги
                    IconButton(onClick = { uiState.bookId?.let { onNavigateToSettings(it) } }) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки книги")
                    }
                }
            )
        }
    ) { padding ->
        when {
            uiState.isLoading -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            uiState.error != null -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text("Ошибка: ${uiState.error}")
                }
            }
            uiState.bookDetails != null -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    uiState.bookDetails.volumes.forEach { volume ->
                        stickyHeader {
                            Surface(modifier = Modifier.fillParentMaxWidth()) {
                                Text(
                                    text = volume.title,
                                    style = MaterialTheme.typography.titleLarge,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                        }
                        items(volume.chapters, key = { it.id }) { chapter ->
                            ChapterItem(
                                chapter = chapter,
                                onClick = { onChapterClick(chapter.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterItem(
    chapter: UiChapter,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Text(
            text = chapter.title,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.titleMedium
        )
    }
}


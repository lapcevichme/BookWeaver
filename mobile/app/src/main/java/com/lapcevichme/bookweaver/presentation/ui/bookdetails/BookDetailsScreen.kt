package com.lapcevichme.bookweaver.presentation.ui.bookdetails


import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle


@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BookDetailsScreen(
    viewModel: BookDetailsViewModel = hiltViewModel(),
    onSettingsClick: (bookId: String) -> Unit,
    onNavigateBack: () -> Unit,
    // <-- НАЧАЛО НОВОГО КОДА -->
    onChapterClick: (bookId: String, chapterId: String) -> Unit
    // <-- КОНЕЦ НОВОГО КОДА -->
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.bookDetails?.title ?: "Загрузка...", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = { uiState.bookId?.let { onSettingsClick(it) } }) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки книги")
                    }
                }
            )
        }
    ) { padding ->
        val volumes = uiState.bookDetails?.volumes ?: emptyList()
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            volumes.forEach { volume ->
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
                    // <-- НАЧАЛО НОВОГО КОДА -->
                    ChapterItem(
                        chapter = chapter,
                        onClick = {
                            uiState.bookId?.let { bookId ->
                                onChapterClick(bookId, chapter.id)
                            }
                        }
                    )
                    // <-- КОНЕЦ НОВОГО КОДА -->
                }
            }
        }
    }
}

@Composable
private fun ChapterItem(
    chapter: UiChapter,
    // <-- НАЧАЛО НОВОГО КОДА -->
    onClick: () -> Unit
    // <-- КОНЕЦ НОВОГО КОДА -->
) {
    // <-- НАЧАЛО НОВОГО КОДА -->
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        // <-- КОНЕЦ НОВОГО КОДА -->
        Text(
            text = chapter.title,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.titleMedium
        )
    }
}

package com.lapcevichme.bookweaver.presentation.ui.bookdetails

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lapcevichme.bookweaver.presentation.ui.book_details.mapper.UiChapter


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailsScreen(
    viewModel: BookDetailsViewModel = hiltViewModel(),
    onSettingsClick: (bookId: String) -> Unit,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                // ИСПРАВЛЕНО: Берем title из bookDetails, если он загружен
                title = { Text(uiState.bookDetails?.title ?: "Загрузка...", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    // ИСПРАВЛЕНО: Используем bookId напрямую из uiState
                    IconButton(onClick = { uiState.bookId?.let { onSettingsClick(it) } }) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки книги")
                    }
                }
            )
        }
    ) { padding ->
        // ИСПРАВЛЕНО: Показываем главы только если они загружены
        val chapters = uiState.bookDetails?.chapters ?: emptyList()
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(chapters, key = { it.id }) { chapter ->
                ChapterItem(chapter)
            }
        }
    }
}

@Composable
private fun ChapterItem(chapter: UiChapter) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = chapter.title,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.titleMedium
        )
    }
}

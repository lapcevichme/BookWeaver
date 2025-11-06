package com.lapcevichme.bookweaver.features.characterdetails

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lapcevichme.bookweaver.core.ui.components.ExpandableSpoilerCard

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CharacterDetailsScreen(
    viewModel: CharacterDetailsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.characterDetails?.name ?: "Загрузка...", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { padding ->
        when {
            uiState.isLoading -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(padding), contentAlignment = Alignment.Center
                ) {
                    LoadingIndicator()
                }
            }

            uiState.error != null -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(padding), contentAlignment = Alignment.Center
                ) {
                    Text("Ошибка: ${uiState.error}")
                }
            }

            uiState.characterDetails != null -> {
                CharacterDetailsContent(
                    details = uiState.characterDetails!!,
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }
}

/**
 * Основной контент экрана, теперь с разделением на спойлеры.
 */
@Composable
private fun CharacterDetailsContent(
    details: UiCharacterDetails,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Безопасная информация
        item {
            // Безопасное описание
            Text(details.spoilerFreeDescription, style = MaterialTheme.typography.bodyLarge)

            // Псевдонимы
            if (details.aliases.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("Также известен как:", style = MaterialTheme.typography.titleMedium)
                Text(
                    details.aliases.joinToString(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic
                )
            }
        }

        // Спойлеры
        item {
            ExpandableSpoilerCard(
                summaryTitle = "Полное описание (Спойлеры!)",
                content = {
                    Text(
                        text = details.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
        }

        if (details.chapterMentions.isNotEmpty()) {
            item {
                ExpandableSpoilerCard(
                    summaryTitle = "Упоминания в главах (${details.chapterMentions.size})",
                    content = {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            details.chapterMentions.forEachIndexed { index, mention ->
                                ChapterMentionItem(mention)
                                if (index < details.chapterMentions.size - 1) {
                                    HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
                                }
                            }
                        }
                    }
                )
            }
        }
    }
}

/**
 * Элемент для одного упоминания в главе.
 * Теперь он не имеет разделителя, т.к. разделитель
 * управляется в цикле выше.
 */
@Composable
private fun ChapterMentionItem(mention: UiChapterMention) {
    Column {
        Text(mention.chapterTitle, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(mention.summary, style = MaterialTheme.typography.bodyMedium)
    }
}

package com.lapcevichme.bookweaver.presentation.ui.character.charactersdetails

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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

@OptIn(ExperimentalMaterial3Api::class)
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
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            uiState.error != null -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
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
        item {
            Text(details.description, style = MaterialTheme.typography.bodyLarge)
        }

        if (details.aliases.isNotEmpty()) {
            item {
                Text("Также известен как:", style = MaterialTheme.typography.titleMedium)
                Text(details.aliases.joinToString(), style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
            }
        }

        if (details.chapterMentions.isNotEmpty()) {
            item {
                Text("Упоминания в главах", style = MaterialTheme.typography.titleLarge)
            }
            items(details.chapterMentions) { mention ->
                ChapterMentionItem(mention)
            }
        }
    }
}

@Composable
private fun ChapterMentionItem(mention: UiChapterMention) {
    Column {
        Text(mention.chapterTitle, style = MaterialTheme.typography.titleMedium)
        Text(mention.summary, style = MaterialTheme.typography.bodyMedium)
        HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
    }
}

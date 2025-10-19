package com.lapcevichme.bookweaver.presentation.ui.details

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChapterDetailsScreen(
    viewModel: ChapterDetailsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()
    val tabTitles = listOf("Сводка", "Сценарий", "Оригинал")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.chapterTitle, maxLines = 1) },
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

            if (uiState.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (uiState.error != null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Ошибка: ${uiState.error}")
                }
            } else {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    when (page) {
                        0 -> SummaryContent(uiState.details?.teaser ?: "", uiState.details?.synopsis ?: "")
                        1 -> ScenarioContent(uiState.details?.scenario ?: emptyList())
                        2 -> OriginalTextContent(uiState.details?.originalText ?: "")
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
private fun ScenarioContent(scenario: List<UiScenarioEntry>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(scenario, key = { it.id }) { entry ->
            Text(
                text = buildString {
                    append(entry.speaker)
                    append(": ")
                    append(entry.text)
                },
                fontWeight = if (entry.speaker != "Рассказчик") FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

@Composable
private fun OriginalTextContent(text: String) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}


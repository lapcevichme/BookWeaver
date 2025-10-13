package com.lapcevichme.bookweaverdesktop.ui


import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDashboardScreen(
    onProjectClick: (bookName: String) -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: DashboardViewModel = koinInject()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BookWeaver: Панель Проектов") },
                actions = {
                    IconButton(onClick = { viewModel.loadProjects() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Обновить")
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Filled.Settings, contentDescription = "Настройки")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("Импорт") },
                icon = { Icon(Icons.Filled.Add, contentDescription = "Импорт") },
                onClick = { viewModel.importNewBook() }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            when {
                uiState.isLoading -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(48.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("Загрузка проектов...", style = MaterialTheme.typography.titleMedium)
                }
                uiState.errorMessage != null -> Text("Ошибка: ${uiState.errorMessage}")
                uiState.projects.isEmpty() -> Text("Проектов пока нет. Нажмите +, чтобы импортировать книгу.")
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(uiState.projects) { project ->
                            ProjectListItem(
                                projectInfo = project,
                                onClick = { onProjectClick(project.name) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectListItem(projectInfo: ProjectInfo, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(text = projectInfo.name, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { projectInfo.progress },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = projectInfo.status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}


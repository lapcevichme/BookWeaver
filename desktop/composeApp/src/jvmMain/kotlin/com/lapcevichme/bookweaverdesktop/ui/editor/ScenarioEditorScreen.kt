package com.lapcevichme.bookweaverdesktop.ui.editor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScenarioEditorScreen(
    bookName: String,
    volume: Int,
    chapter: Int,
    onBackClick: () -> Unit,
    viewModel: ScenarioEditorViewModel = koinInject { parametersOf(bookName, volume, chapter) }
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Редактор: Т.${volume}, Г.${chapter}") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    Button(
                        onClick = { viewModel.saveScenario() },
                        enabled = !uiState.isSaving && uiState.replicas.isNotEmpty()
                    ) {
                        if (uiState.isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Save, contentDescription = "Сохранить")
                            Spacer(Modifier.width(8.dp))
                            Text("Сохранить")
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            when {
                uiState.isLoading -> CircularProgressIndicator()
                uiState.errorMessage != null -> Text("Ошибка: ${uiState.errorMessage}", color = MaterialTheme.colorScheme.error)
                uiState.replicas.isNotEmpty() -> {
                    ScenarioEditor(
                        replicas = uiState.replicas,
                        onReplicaChange = viewModel::updateReplicaText
                    )
                }
                else -> Text("Сценарий не найден или пуст.")
            }
        }
    }
}

@Composable
private fun ScenarioEditor(
    replicas: List<UiReplica>,
    onReplicaChange: (id: String, newText: String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(items = replicas, key = { it.id }) { replica ->
            ReplicaEditorItem(
                replica = replica,
                onTextChange = { newText -> onReplicaChange(replica.id, newText) }
            )
        }
    }
}

@Composable
private fun ReplicaEditorItem(
    replica: UiReplica,
    onTextChange: (String) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = replica.speaker,
                onValueChange = { /* TODO: Implement speaker change */ },
                label = { Text("Спикер") },
                modifier = Modifier.weight(0.3f)
            )
            OutlinedTextField(
                value = replica.text,
                onValueChange = onTextChange,
                label = { Text("Текст реплики") },
                modifier = Modifier.weight(0.7f)
            )
        }
    }
}

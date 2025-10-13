package com.lapcevichme.bookweaverdesktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManifestEditorScreen(
    bookName: String,
    onBackClick: () -> Unit,
    viewModel: ManifestEditorViewModel = koinInject { parametersOf(bookName) }
) {
    val uiState by viewModel.uiState.collectAsState()
    var manifestText by remember(uiState.manifestContent) { mutableStateOf(uiState.manifestContent) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Редактор манифеста: $bookName") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    Button(
                        onClick = { manifestText?.let { viewModel.saveManifest(it) } },
                        enabled = uiState.isModified && !uiState.isSaving
                    ) {
                        if (uiState.isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
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
                uiState.errorMessage != null -> Text("Ошибка: ${uiState.errorMessage}")
                manifestText != null -> {
                    OutlinedTextField(
                        value = manifestText!!,
                        onValueChange = {
                            manifestText = it
                            viewModel.markAsModified(it)
                        },
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                        label = { Text("manifest.json") }
                    )
                }
            }
        }
    }
}

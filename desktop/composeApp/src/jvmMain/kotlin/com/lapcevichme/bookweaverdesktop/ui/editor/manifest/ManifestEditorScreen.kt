package com.lapcevichme.bookweaverdesktop.ui.editor.manifest

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
                        onClick = { viewModel.saveManifest() },
                        enabled = uiState.isModified && !uiState.isSaving && uiState.isJsonValid
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
        // ИЗМЕНЕНО: Используем Column для размещения поля ввода и текста ошибки
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp),
        ) {
            when {
                uiState.isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                // ИЗМЕНЕНО: Ошибка загрузки отображается отдельно
                uiState.errorMessage != null && uiState.manifestContent.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Ошибка загрузки: ${uiState.errorMessage}")
                    }
                }
                // Основное состояние - редактор
                else -> {
                    OutlinedTextField(
                        value = uiState.manifestContent,
                        onValueChange = { viewModel.onContentChange(it) },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                        label = { Text("manifest.json") },
                        // ИЗМЕНЕНО: Поле становится "ошибочным", если JSON невалиден
                        isError = !uiState.isJsonValid
                    )
                    // ИЗМЕНЕНО: Сообщение об ошибке валидации появляется под полем
                    if (!uiState.isJsonValid) {
                        Text(
                            text = "Ошибка: Введенный текст не является корректным JSON.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

package com.lapcevichme.bookweaver.presentation.ui.bookinstall

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookInstallationScreen(
    viewModel: BookInstallationViewModel = hiltViewModel(),
    onInstallationSuccess: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Лаунчер для выбора файла
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri ->
            viewModel.onEvent(InstallationEvent.InstallFromFile(uri))
        }
    )

    // Показываем Snackbar при результате установки
    LaunchedEffect(uiState.installationResult) {
        uiState.installationResult?.let { result ->
            val message = result.fold(
                onSuccess = { "Книга успешно установлена!" },
                onFailure = { "Ошибка: ${it.message}" }
            )
            snackbarHostState.showSnackbar(message)
            viewModel.onEvent(InstallationEvent.ResultHandled) // Сбрасываем результат
            if (result.isSuccess) {
                onInstallationSuccess()
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(title = { Text("Установить книгу") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            OutlinedTextField(
                value = uiState.urlInput,
                onValueChange = { viewModel.onEvent(InstallationEvent.UrlChanged(it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("URL на .bw файл") },
                singleLine = true,
                enabled = !uiState.isLoading
            )

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { viewModel.onEvent(InstallationEvent.InstallFromUrlClicked) },
                enabled = !uiState.isLoading && uiState.urlInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Установить по URL")
            }

            // Заменяем устаревший Divider на новую конструкцию
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f))
                Text("ИЛИ")
                HorizontalDivider(modifier = Modifier.weight(1f))
            }

            Button(
                onClick = { filePickerLauncher.launch("*/*") },
                enabled = !uiState.isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Выбрать файл .bw")
            }

            Spacer(Modifier.height(16.dp))

            AnimatedContent(
                targetState = uiState.isLoading,
                label = "loading-indicator"
            ) { isLoading ->
                if (isLoading) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text("Установка...", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}


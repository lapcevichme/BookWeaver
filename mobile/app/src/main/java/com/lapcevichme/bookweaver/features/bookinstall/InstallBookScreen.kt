package com.lapcevichme.bookweaver.features.bookinstall

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Обновленный экран установки, который теперь тоже "глупый".
 * Принимает uiState и отдает onEvent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstallBookScreen(
    uiState: BookInstallationUiState,
    onEvent: (InstallationEvent) -> Unit,
    onInstallationSuccess: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri -> onEvent(InstallationEvent.InstallFromFile(uri)) }
    )

    val qrCodeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { /* TODO: Handle QR code result and pass it to onEvent */ }
    )

    LaunchedEffect(uiState.installationResult) {
        uiState.installationResult?.let { result ->
            val message = result.fold(
                onSuccess = { "Книга успешно установлена!" },
                onFailure = { "Ошибка: ${it.message}" }
            )
            snackbarHostState.showSnackbar(message)
            onEvent(InstallationEvent.ResultHandled) // Сбрасываем результат
            if (result.isSuccess) {
                onInstallationSuccess()
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { TopAppBar(title = { Text("Добавить книгу") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(
                onClick = { /* TODO: qrCodeLauncher.launch(...) */ },
                enabled = !uiState.isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("Подключиться к серверу по QR")
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f))
                Text("ИЛИ")
                HorizontalDivider(modifier = Modifier.weight(1f))
            }

            OutlinedTextField(
                value = uiState.urlInput,
                onValueChange = { onEvent(InstallationEvent.UrlChanged(it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("URL на .bw файл") },
                singleLine = true,
                enabled = !uiState.isLoading
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onEvent(InstallationEvent.InstallFromUrlClicked) },
                enabled = !uiState.isLoading && uiState.urlInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Скачать по URL")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { filePickerLauncher.launch("*/*") },
                enabled = !uiState.isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Выбрать файл .bw с устройства")
            }
            Spacer(Modifier.height(16.dp))
            AnimatedContent(targetState = uiState.isLoading, label = "loading-indicator") { isLoading ->
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


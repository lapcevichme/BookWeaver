package com.lapcevichme.bookweaver.features.bookinstall

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lapcevichme.bookweaver.domain.model.DownloadProgress
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Обновленный экран установки, который теперь тоже "глупый".
 * Принимает uiState и отдает onEvent.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun InstallBookScreen(
    uiState: BookInstallationUiState,
    onEvent: (InstallationEvent) -> Unit,
    onNavigateBack: () -> Unit,
    onInstallationSuccess: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

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

            // Запускаем Snackbar в отдельной корутине,
            // чтобы он не блокировал навигацию
            scope.launch {
                snackbarHostState.showSnackbar(message)
            }

            onEvent(InstallationEvent.ResultHandled)

            if (result.isSuccess) {
                onInstallationSuccess()
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Добавить книгу") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // КАРТОЧКА ДЛЯ URL
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Link, "Ссылка", modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Загрузить по ссылке", style = MaterialTheme.typography.titleLarge)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Вставьте прямую ссылку на .bw файл",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = uiState.urlInput,
                        onValueChange = { onEvent(InstallationEvent.UrlChanged(it)) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("URL на .bw файл") },
                        singleLine = true,
                        enabled = !uiState.isBusy
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { onEvent(InstallationEvent.InstallFromUrlClicked) },
                        enabled = !uiState.isBusy && uiState.urlInput.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Download, null)
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text("Скачать по URL")
                    }
                }
            }

            // РАЗДЕЛИТЕЛЬ "ИЛИ"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f))
                Text("ИЛИ", style = MaterialTheme.typography.labelMedium)
                HorizontalDivider(modifier = Modifier.weight(1f))
            }

            // КАРТОЧКА ДЛЯ ФАЙЛА
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.UploadFile, "Файл", modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Выбрать с устройства", style = MaterialTheme.typography.titleLarge)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Выберите .bw файл из памяти телефона",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = { filePickerLauncher.launch("*/*") },
                        enabled = !uiState.isBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Выбрать файл .bw с устройства")
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // КАРТОЧКА ДЛЯ QR
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.QrCodeScanner, "QR", modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Подключиться к серверу", style = MaterialTheme.typography.titleLarge)
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { /* TODO: qrCodeLauncher.launch(...) */ },
                        enabled = !uiState.isBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Сканировать QR-код")
                    }
                }
            }
            // Блок индикатора загрузки
            AnimatedContent(
                // Следим за классом состояния, а не за значением, чтобы анимация
                // не срабатывала на каждом обновлении процентов
                targetState = uiState.downloadProgress::class,
                label = "loading-indicator",
                modifier = Modifier
                    .padding(vertical = 24.dp)
                    .fillMaxWidth()
                    .heightIn(min = 48.dp), // Задаем минимальную высоту
                transitionSpec = {
                    fadeIn(animationSpec = tween(300)).togetherWith(fadeOut(animationSpec = tween(300)))
                }
            ) { progressStateClass ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    when (val progress = uiState.downloadProgress) {
                        is DownloadProgress.Downloading -> {
                            // Вложенный Column, чтобы текст был ПОД индикатором
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                val percent: Float
                                val text: String

                                if (progress.totalBytes > 0) {
                                    // Сервер дал размер
                                    percent = (progress.bytesDownloaded.toFloat() / progress.totalBytes.toFloat())
                                    text = "Скачивание... ${formatBytes(progress.bytesDownloaded)} / ${formatBytes(progress.totalBytes)} (${(percent * 100).roundToInt()}%)"
                                } else {
                                    // Сервер не дал размер, показываем только скачанные МБ
                                    percent = 0f // Будет "бесконечный" LinearProgressIndicator
                                    text = "Скачивание... ${formatBytes(progress.bytesDownloaded)}"
                                }

                                if (progress.totalBytes > 0) {
                                    LinearWavyProgressIndicator(
                                        progress = { percent },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                } else {
                                    // "Бесконечный" прогресс-бар
                                    LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }

                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = text,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                        DownloadProgress.Installing -> {
                            // Вложенный Column, чтобы текст был ПОД индикатором
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                LoadingIndicator()
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Установка...", style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        DownloadProgress.Idle -> {
                            // Ничего не показываем, когда бездействуем
                            // Spacer нужен, чтобы `heightIn(min = 48.dp)` работал
                            Spacer(modifier = Modifier.height(48.dp))
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

// TODO - в core
/**
 * Форматирует байты в читаемый вид (КБ, МБ, ГБ)
 */
private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kBytes = bytes / 1024.0
    if (kBytes < 1024) return "${String.format("%.1f", kBytes)} KB"
    val mBytes = kBytes / 1024.0
    if (mBytes < 1024) return "${String.format("%.1f", mBytes)} MB"
    val gBytes = mBytes / 1024.0
    return "${String.format("%.1f", gBytes)} GB"
}
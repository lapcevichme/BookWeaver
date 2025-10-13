package com.lapcevichme.bookweaverdesktop.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.lapcevichme.bookweaverdesktop.backend.BackendProcessManager
import com.lapcevichme.bookweaverdesktop.model.WsServerState
import com.lapcevichme.bookweaverdesktop.settings.AppSettings
import com.lapcevichme.bookweaverdesktop.ui.MainViewModel
import org.koin.compose.koinInject
import java.awt.image.BufferedImage

private const val QR_SIZE = 256

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsAndAssetsScreen(
    onBackClick: () -> Unit,
    mainViewModel: MainViewModel = koinInject(),
    settingsViewModel: SettingsAndAssetsViewModel = koinInject()
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Настройки", "Бэкенд", "Подключение", "Конфигурация AI", "Библиотека голосов", "Словарь")
    val uiState by settingsViewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Панель Управления") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(Modifier.fillMaxSize().padding(paddingValues)) {
            ScrollableTabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        text = { Text(title) },
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        enabled = index < 4 // Пока активны только первые 4 вкладки
                    )
                }
            }
            Box(Modifier.fillMaxSize().padding(16.dp)) {
                when (selectedTab) {
                    0 -> SettingsTab(uiState, settingsViewModel::saveSettings)
                    1 -> BackendTab(mainViewModel)
                    2 -> ConnectionTab(mainViewModel)
                    3 -> ConfigTab(uiState, settingsViewModel::saveConfig, settingsViewModel::loadData)
                    4 -> PlaceholderTab("Управление моделями голосов", "Здесь вы сможете добавлять, удалять и настраивать референсы голосов для Voice Conversion.")
                    5 -> PlaceholderTab("Словарь произношений", "Здесь вы сможете управлять словарем для корректной озвучки специфичных терминов и имен.")
                }
            }
        }
    }
}

@Composable
private fun SettingsTab(
    state: SettingsUiState,
    onSave: (AppSettings) -> Unit
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when(state) {
            is SettingsUiState.Loading -> CircularProgressIndicator()
            is SettingsUiState.Error -> Text("Критическая ошибка: ${state.message}", color = MaterialTheme.colorScheme.error)
            is SettingsUiState.Loaded -> {
                SettingsForm(settings = state.settings, onSave = onSave)
            }
        }
    }
}

@Composable
private fun SettingsForm(
    settings: AppSettings,
    onSave: (AppSettings) -> Unit
) {
    var pythonPath by remember(settings.pythonExecutablePath) { mutableStateOf(settings.pythonExecutablePath) }
    var backendPath by remember(settings.backendWorkingDirectory) { mutableStateOf(settings.backendWorkingDirectory) }

    Column(
        modifier = Modifier.widthIn(max = 600.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        OutlinedTextField(
            value = pythonPath,
            onValueChange = { pythonPath = it },
            label = { Text("Путь к исполняемому файлу Python") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = backendPath,
            onValueChange = { backendPath = it },
            label = { Text("Рабочая директория бэкенда") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { onSave(AppSettings(pythonExecutablePath = pythonPath, backendWorkingDirectory = backendPath)) },
            modifier = Modifier.align(Alignment.End)
        ) {
            Text("Сохранить")
        }
    }
}


@Composable
private fun BackendTab(viewModel: MainViewModel) {
    val backendState by viewModel.backendState.collectAsState()
    val backendLogs by viewModel.backendLogs.collectAsState()
    val logScrollState = rememberLazyListState()

    LaunchedEffect(backendLogs.size) {
        if (backendLogs.isNotEmpty()) {
            logScrollState.animateScrollToItem(backendLogs.lastIndex)
        }
    }

    Row(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(300.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Управление Бэкендом", style = MaterialTheme.typography.titleMedium)

            when (val state = backendState) {
                is BackendProcessManager.State.STOPPED -> Button(onClick = { viewModel.startBackend() }) { Text("Запустить") }
                is BackendProcessManager.State.STARTING -> CircularProgressIndicator()
                is BackendProcessManager.State.RUNNING_HEALTHY -> {
                    Text("✅ Сервер запущен", color = Color(0xFF008000))
                    Button(onClick = { viewModel.stopBackend() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                        Text("Остановить")
                    }
                }
                is BackendProcessManager.State.FAILED -> {
                    Text("❌ Ошибка: ${state.reason}", color = MaterialTheme.colorScheme.error)
                    Button(onClick = { viewModel.startBackend() }) { Text("Попробовать снова") }
                }
                is BackendProcessManager.State.RUNNING_INITIALIZING -> {
                    CircularProgressIndicator()
                    Text("Инициализация AI...")
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(1f)
                .background(Color.DarkGray)
                .padding(16.dp)
        ) {
            Text("Логи Python-сервера", style = MaterialTheme.typography.titleSmall, color = Color.White)
            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.fillMaxSize(), state = logScrollState) {
                items(backendLogs) { logLine ->
                    Text(
                        text = logLine,
                        color = when {
                            "ERROR" in logLine || "CRITICAL" in logLine -> Color.Red
                            "WARNING" in logLine -> Color.Yellow
                            else -> Color.White
                        },
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionTab(viewModel: MainViewModel) {
    val serverState by viewModel.webSocketServerState.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (val currentState = serverState) {
            is WsServerState.Disconnected -> {
                Text("Сервер остановлен.")
                Spacer(Modifier.height(16.dp))
                Button(onClick = { viewModel.startWebSocketServer() }) {
                    Text("Начать подключение")
                }
            }
            is WsServerState.ReadyForConnection -> {
                Text("Сервер готов к подключению", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { viewModel.showConnectionInstructions() }) {
                    Text("Показать QR-код")
                }
            }
            is WsServerState.AwaitingConnection -> {
                Text("Отсканируйте QR-код", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                QrCodeImage(currentState.qrCodeData)
            }
            is WsServerState.PeerConnected -> {
                Text("Устройство подключено!", style = MaterialTheme.typography.titleLarge, color = Color(0xFF008000))
                Spacer(Modifier.height(8.dp))
                Text("Адрес: ${currentState.peerInfo}")
            }
            is WsServerState.Error -> {
                Text("Произошла ошибка:", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                Text(currentState.message, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ConfigTab(
    state: SettingsUiState,
    onSave: (String) -> Unit,
    onReload: () -> Unit
) {
    when (state) {
        is SettingsUiState.Loading -> CircularProgressIndicator()
        is SettingsUiState.Error -> Text("Критическая ошибка: ${state.message}", color = MaterialTheme.colorScheme.error)
        is SettingsUiState.Loaded -> {
            if (state.configError != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Ошибка загрузки config.py:", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
                    Text(state.configError, color = MaterialTheme.colorScheme.error)
                    Text("Пожалуйста, укажите корректный путь к директории бэкенда на вкладке 'Настройки' и попробуйте снова.", style = MaterialTheme.typography.bodySmall)
                }
            } else if (state.configContent != null) {
                var editorText by remember(state.configContent) { mutableStateOf(state.configContent) }
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Text("Редактирование config.py", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Внимание: Изменение этого файла требует перезапуска AI Backend!",
                        color = MaterialTheme.colorScheme.secondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Button(
                            onClick = { onSave(editorText) },
                            enabled = editorText != state.configContent
                        ) {
                            Text("Сохранить изменения")
                        }
                        Button(onClick = onReload) {
                            Text("Обновить")
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    SelectionContainer(Modifier.fillMaxSize()) {
                        OutlinedTextField(
                            value = editorText,
                            onValueChange = { editorText = it },
                            modifier = Modifier.fillMaxSize(),
                            textStyle = LocalTextStyle.current.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            label = { Text("Содержимое config.py") }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceholderTab(title: String, description: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Text(description, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun QrCodeImage(text: String) {
    val imageBitmap = remember(text) { generateQrCodeBitmap(text) }
    Image(
        bitmap = imageBitmap,
        contentDescription = "QR code for connection",
        modifier = Modifier.size(QR_SIZE.dp)
    )
}

private fun generateQrCodeBitmap(text: String): ImageBitmap {
    val qrCodeWriter = QRCodeWriter()
    val bitMatrix = qrCodeWriter.encode(text, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE)
    val bufferedImage = BufferedImage(QR_SIZE, QR_SIZE, BufferedImage.TYPE_INT_RGB)
    for (x in 0 until QR_SIZE) {
        for (y in 0 until QR_SIZE) {
            bufferedImage.setRGB(x, y, if (bitMatrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        }
    }
    return bufferedImage.toComposeImageBitmap()
}


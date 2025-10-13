package com.lapcevichme.bookweaverdesktop.ui

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
import com.lapcevichme.bookweaverdesktop.model.ServerState
import com.lapcevichme.bookweaverdesktop.settings.AppSettings
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
                        enabled = index < 4
                    )
                }
            }
            when (selectedTab) {
                0 -> SettingsTab(settingsViewModel)
                1 -> BackendTab(mainViewModel)
                2 -> ConnectionTab(mainViewModel)
                3 -> ConfigTab(settingsViewModel) // Новая вкладка
                4 -> PlaceholderTab("Управление моделями голосов", "Здесь вы сможете добавлять, удалять и настраивать референсы голосов для Voice Conversion.")
                5 -> PlaceholderTab("Словарь произношений", "Здесь вы сможете управлять словарем для корректной озвучки специфичных терминов и имен.")
            }
        }
    }
}

// Вкладка с основными настройками (пути)
@Composable
private fun SettingsTab(viewModel: SettingsAndAssetsViewModel) {
    val settingsState by viewModel.uiState.collectAsState()

    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        when(val state = settingsState) {
            is SettingsUiState.Loading -> CircularProgressIndicator()
            is SettingsUiState.Error -> Text("Ошибка: ${state.message}")
            is SettingsUiState.Success -> {
                SettingsForm(settings = state.settings, onSave = viewModel::saveSettings)
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


// Вкладка управления Python-бэкендом
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
        // Левая панель - управление
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

            when (backendState) {
                BackendProcessManager.State.STOPPED -> Button(onClick = { viewModel.startBackend() }) { Text("Запустить") }
                BackendProcessManager.State.STARTING -> CircularProgressIndicator()
                BackendProcessManager.State.RUNNING_HEALTHY -> {
                    Text("✅ Сервер запущен", color = Color(0xFF008000))
                    Button(onClick = { viewModel.stopBackend() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                        Text("Остановить")
                    }
                }
                is BackendProcessManager.State.FAILED -> {
                    Text("❌ Ошибка запуска", color = MaterialTheme.colorScheme.error)
                    Button(onClick = { viewModel.startBackend() }) { Text("Попробовать снова") }
                }
                BackendProcessManager.State.RUNNING_INITIALIZING -> {
                    CircularProgressIndicator()
                    Text("Инициализация AI...")
                }
            }
        }

        // Правая панель - логи
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

// Вкладка для подключения мобильного клиента
@Composable
private fun ConnectionTab(viewModel: MainViewModel) {
    val serverState by viewModel.webSocketServerState.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (val currentState = serverState) {
            is ServerState.Disconnected -> {
                Text("Сервер остановлен.")
                Spacer(Modifier.height(16.dp))
                Button(onClick = { viewModel.startWebSocketServer() }) {
                    Text("Начать подключение")
                }
            }
            is ServerState.ReadyForConnection -> {
                Text("Сервер готов к подключению", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { viewModel.showConnectionInstructions() }) {
                    Text("Показать QR-код")
                }
            }
            is ServerState.AwaitingConnection -> {
                Text("Отсканируйте QR-код", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                QrCodeImage(currentState.qrCodeData)
            }
            is ServerState.PeerConnected -> {
                Text("Устройство подключено!", style = MaterialTheme.typography.titleLarge, color = Color(0xFF008000))
                Spacer(Modifier.height(8.dp))
                Text("Адрес: ${currentState.peerInfo}")
            }
            is ServerState.Error -> {
                Text("Произошла ошибка:", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                Text(currentState.message, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

// Редактор конфигурации
@Composable
private fun ConfigTab(viewModel: SettingsAndAssetsViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    when (val state = uiState) {
        is SettingsUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is SettingsUiState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(state.message) }
        is SettingsUiState.Success -> {
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
                        onClick = { viewModel.saveConfig(editorText) },
                        enabled = editorText.isNotBlank() && "❌" !in state.configContent
                    ) {
                        Text("Сохранить изменения")
                    }
                    Button(onClick = { viewModel.loadData() }) {
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
                            color = if ("❌" in state.configContent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        ),
                        label = { Text("Содержимое config.py") }
                    )
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

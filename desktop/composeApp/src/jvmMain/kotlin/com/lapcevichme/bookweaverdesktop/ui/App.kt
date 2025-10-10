package com.lapcevichme.bookweaverdesktop.ui

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.*
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
import java.awt.image.BufferedImage

private const val QR_SIZE = 256

@Composable
@Preview
fun App(viewModel: MainViewModel) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Подключение", "AI Backend", "Конфигурация")
    val scaffoldState = rememberScaffoldState()

    LaunchedEffect(Unit) {
        viewModel.uiMessages.collect { message ->
            scaffoldState.snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(scaffoldState = scaffoldState) {
        Column(Modifier.fillMaxSize()) {
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        text = { Text(title) },
                        selected = selectedTab == index,
                        onClick = { selectedTab = index }
                    )
                }
            }
            when (selectedTab) {
                0 -> MobileConnectionTab(viewModel)
                1 -> AiBackendTab(viewModel)
                2 -> ConfigEditorTab(viewModel)
            }
        }
    }
}

@Composable
fun MobileConnectionTab(viewModel: MainViewModel) {
    val serverState by viewModel.webSocketServerState.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (val currentState = serverState) {
            is ServerState.Disconnected -> {
                Text("Server stopped.")
                Spacer(Modifier.height(16.dp))
                Button(onClick = { viewModel.startWebSocketServer() }) {
                    Text("Start Connection Server")
                }
            }

            is ServerState.ReadyForConnection -> {
                Text("Server is Ready for Connection", style = MaterialTheme.typography.h6)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { viewModel.showConnectionInstructions() }) {
                    Text("Показать QR-код для подключения")
                }
            }

            is ServerState.AwaitingConnection -> {
                Text("Scan QR code with your mobile app", style = MaterialTheme.typography.h6)
                Spacer(Modifier.height(16.dp))
                QrCodeImage(currentState.qrCodeData)
                Spacer(Modifier.height(16.dp))
                Text("Awaiting connection...", style = MaterialTheme.typography.caption)
            }

            is ServerState.PeerConnected -> {
                Text("Device connected!", style = MaterialTheme.typography.h6, color = Color(0xFF008000))
                Spacer(Modifier.height(8.dp))
                Text("Address: ${currentState.peerInfo}")
            }

            is ServerState.Error -> {
                Text("An error occurred:", style = MaterialTheme.typography.h6, color = MaterialTheme.colors.error)
                Spacer(Modifier.height(8.dp))
                Text(currentState.message, color = MaterialTheme.colors.error)
            }
        }
    }
}


@Composable
fun AiBackendTab(viewModel: MainViewModel) {
    val backendState by viewModel.backendState.collectAsState()
    val backendLogs by viewModel.backendLogs.collectAsState()
    val taskStatus by viewModel.taskStatus.collectAsState()
    val projects by viewModel.projects.collectAsState()
    var selectedProject by remember { mutableStateOf<String?>(null) }

    val logScrollState = rememberLazyListState()

    LaunchedEffect(backendLogs.size) {
        if (backendLogs.isNotEmpty()) {
            logScrollState.animateScrollToItem(backendLogs.lastIndex)
        }
    }

    // Load projects when the backend becomes ready
    LaunchedEffect(backendState) {
        if (backendState == BackendProcessManager.State.RUNNING_HEALTHY) {
            viewModel.loadProjects()
        }
    }

    Row(Modifier.fillMaxSize()) {
        // Левая панель - управление
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(400.dp)
                .background(MaterialTheme.colors.surface)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Управление Бэкендом", style = MaterialTheme.typography.h6)
            Spacer(Modifier.height(16.dp))

            when (backendState) {
                BackendProcessManager.State.STOPPED -> Button(onClick = { viewModel.startBackend() }) { Text("Запустить AI Backend") }
                BackendProcessManager.State.STARTING -> {
                    CircularProgressIndicator()
                    Text("Запуск...", style = MaterialTheme.typography.caption)
                }
                BackendProcessManager.State.RUNNING_HEALTHY -> {
                    Text("✅ Сервер запущен", color = Color(0xFF008000))
                    Button(onClick = { viewModel.stopBackend() }, colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error)) {
                        Text("Остановить AI Backend")
                    }
                }
                is BackendProcessManager.State.FAILED -> {
                    Text("❌ Ошибка запуска", color = MaterialTheme.colors.error)
                    Button(onClick = { viewModel.startBackend() }) { Text("Попробовать снова") }
                }
                BackendProcessManager.State.RUNNING_INITIALIZING -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Text("Инициализация AI моделей...", style = MaterialTheme.typography.caption)
                        Spacer(Modifier.weight(1f))
                        Button(onClick = { viewModel.stopBackend() }, colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error)) {
                            Text("Отмена")
                        }
                    }
                }
            }
            Divider(Modifier.padding(vertical = 24.dp))

            // Section for Projects
            ProjectManagementSection(
                projects = projects,
                selectedProject = selectedProject,
                onProjectSelected = { selectedProject = it },
                onImportClick = { viewModel.importNewBook() },
                onRefreshClick = { viewModel.loadProjects() },
                isEnabled = backendState == BackendProcessManager.State.RUNNING_HEALTHY
            )


            Divider(Modifier.padding(vertical = 24.dp))

            Text("Задачи", style = MaterialTheme.typography.h6)
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    selectedProject?.let { book ->
                        // For simplicity, we still use chapter 1, vol 1
                        viewModel.startTtsTask(book, 1, 1)
                    }
                },
                enabled = selectedProject != null && backendState == BackendProcessManager.State.RUNNING_HEALTHY && taskStatus.status != "processing"
            ) {
                Text("Запустить TTS для проекта")
            }
            Spacer(Modifier.height(16.dp))

            if (taskStatus.taskId.isNotBlank()) {
                Card(elevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp)) {
                        Text("Текущая задача:", style = MaterialTheme.typography.subtitle2)
                        Text("ID: ${taskStatus.taskId.take(8)}...")
                        Text("Статус: ${taskStatus.status}")
                        Text(taskStatus.message, fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                        if (taskStatus.status == "processing") {
                            LinearProgressIndicator(
                                progress = taskStatus.progress.toFloat(),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
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
            Text("Логи Python-сервера", style = MaterialTheme.typography.h6, color = Color.White)
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = logScrollState
            ) {
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
fun ProjectManagementSection(
    projects: List<String>,
    selectedProject: String?,
    onProjectSelected: (String) -> Unit,
    onImportClick: () -> Unit,
    onRefreshClick: () -> Unit,
    isEnabled: Boolean
) {
    var expanded by remember { mutableStateOf(false) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Проекты", style = MaterialTheme.typography.h6)
        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onImportClick, enabled = isEnabled) {
                Text("Импорт книги")
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onRefreshClick, enabled = isEnabled) {
                Text("Обновить список")
            }
        }
        Spacer(Modifier.height(16.dp))

        Box {
            OutlinedTextField(
                value = selectedProject ?: "Выберите проект",
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.fillMaxWidth().clickable(enabled = isEnabled) { expanded = true },
                label = { Text("Выбранный проект") }
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.fillMaxWidth(0.8f)
            ) {
                if (projects.isEmpty()) {
                    DropdownMenuItem(onClick = { expanded = false }) {
                        Text("Нет доступных проектов")
                    }
                } else {
                    projects.forEach { project ->
                        DropdownMenuItem(onClick = {
                            onProjectSelected(project)
                            expanded = false
                        }) {
                            Text(project)
                        }
                    }
                }
            }
        }
    }
}


@Composable
fun ConfigEditorTab(viewModel: MainViewModel) {
    val configContent by viewModel.configContent.collectAsState()
    var editorText by remember { mutableStateOf(configContent) }

    LaunchedEffect(configContent) {
        editorText = configContent
    }

    Column(Modifier.fillMaxSize()) {
        Text("Редактирование config.py", style = MaterialTheme.typography.h5, modifier = Modifier.padding(16.dp))
        Text(
            "Внимание: Изменение этого файла требует перезапуска AI Backend!",
            color = MaterialTheme.colors.secondary,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Button(
                onClick = { viewModel.saveConfig(editorText) },
                enabled = editorText.isNotBlank() && "❌" !in configContent
            ) {
                Text("Сохранить изменения")
            }
            Button(onClick = { viewModel.loadConfig() }) {
                Text("Обновить (сбросить несохраненное)")
            }
        }
        Spacer(Modifier.height(8.dp))

        SelectionContainer(Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = editorText,
                onValueChange = { editorText = it },
                modifier = Modifier.fillMaxSize().padding(16.dp).background(Color(0xFF2B2B2B)),
                textStyle = LocalTextStyle.current.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = if ("❌" in configContent) Color.Red else Color.White
                ),
                label = { Text("Содержимое config.py (Python)") },
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    textColor = Color.White,
                    focusedBorderColor = MaterialTheme.colors.primary,
                    unfocusedBorderColor = Color.LightGray,
                    backgroundColor = Color(0xFF2B2B2B)
                )
            )
        }
    }
}


@Composable
fun QrCodeImage(text: String) {
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

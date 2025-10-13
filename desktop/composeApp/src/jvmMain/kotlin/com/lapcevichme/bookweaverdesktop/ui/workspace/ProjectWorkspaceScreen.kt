package com.lapcevichme.bookweaverdesktop.ui.workspace

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lapcevichme.bookweaverdesktop.domain.model.Chapter
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectWorkspaceScreen(
    bookName: String,
    onChapterClick: (volume: Int, chapter: Int) -> Unit,
    onBackClick: () -> Unit,
    viewModel: WorkspaceViewModel = koinInject { parametersOf(bookName) }
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.userMessages.collect { message ->
            scope.launch {
                snackbarHostState.showSnackbar(message)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Проект: ${uiState.bookName}") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.loadProjectDetails() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Обновить")
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
                uiState.projectDetails != null -> {
                    Row(modifier = Modifier.fillMaxSize()) {
                        ChapterNavigationPanel(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            chapters = uiState.projectDetails!!.chapters,
                            selectedChapter = uiState.selectedChapter,
                            onChapterSelected = { viewModel.selectChapter(it) }
                        )
                        VerticalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        PipelinePanel(
                            modifier = Modifier.weight(2f).fillMaxHeight().padding(16.dp),
                            selectedChapter = uiState.selectedChapter,
                            activeTask = uiState.activeTask,
                            onGenerateScenario = { vol, chap -> viewModel.generateScenario(vol, chap) },
                            onSynthesizeAudio = { vol, chap -> viewModel.synthesizeAudio(vol, chap) },
                            onEditScenario = { vol, chap -> onChapterClick(vol, chap) }
                        )
                        VerticalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        AssetsPanel(
                            modifier = Modifier.weight(1.5f).fillMaxHeight().padding(16.dp),
                            assetsState = uiState.assets,
                            viewModel = viewModel
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterNavigationPanel(
    modifier: Modifier = Modifier,
    chapters: List<Chapter>,
    selectedChapter: Chapter?,
    onChapterSelected: (Chapter) -> Unit
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item {
            Text(
                "Главы",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp)
            )
        }
        items(chapters) { chapter ->
            val isSelected =
                chapter.volumeNumber == selectedChapter?.volumeNumber && chapter.chapterNumber == selectedChapter.chapterNumber
            val containerColor = when {
                isSelected -> MaterialTheme.colorScheme.primaryContainer
                else -> Color.Transparent
            }
            Box {
                Text(
                    text = "Том ${chapter.volumeNumber}, Глава ${chapter.chapterNumber}",
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onChapterSelected(chapter) }
                        .background(containerColor)
                        .padding(12.dp),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}

@Composable
private fun PipelinePanel(
    modifier: Modifier = Modifier,
    selectedChapter: Chapter?,
    activeTask: ActiveTaskDetails?,
    onGenerateScenario: (Int, Int) -> Unit,
    onSynthesizeAudio: (Int, Int) -> Unit,
    onEditScenario: (Int, Int) -> Unit
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (selectedChapter == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Выберите главу слева", style = MaterialTheme.typography.titleLarge)
            }
        } else {
            val isTaskForThisChapter = activeTask?.let {
                it.volumeNumber == selectedChapter.volumeNumber && it.chapterNumber == selectedChapter.chapterNumber
            } ?: false
            val currentTask = if (isTaskForThisChapter) activeTask else null

            Text(
                "Том ${selectedChapter.volumeNumber}, Глава ${selectedChapter.chapterNumber}",
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(Modifier.height(16.dp))

            PipelineStep(
                title = "1. Генерация сценария",
                isDone = selectedChapter.hasScenario,
                taskDetails = currentTask,
                stepType = TaskType.SCENARIO,
                onClick = { onGenerateScenario(selectedChapter.volumeNumber, selectedChapter.chapterNumber) },
                onEditClick = { onEditScenario(selectedChapter.volumeNumber, selectedChapter.chapterNumber) },
                isEditable = selectedChapter.hasScenario
            )

            PipelineStep(
                title = "2. Синтез аудио",
                isDone = selectedChapter.hasAudio,
                taskDetails = currentTask,
                stepType = TaskType.AUDIO,
                enabled = selectedChapter.hasScenario,
                onClick = { onSynthesizeAudio(selectedChapter.volumeNumber, selectedChapter.chapterNumber) }
            )

            Spacer(Modifier.weight(1f))

            Button(
                onClick = { /* TODO: Implement full pipeline trigger */ },
                enabled = activeTask == null,
                modifier = Modifier.fillMaxWidth(0.8f)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Запустить полный цикл")
                Spacer(Modifier.width(8.dp))
                Text("Запустить полный цикл для главы")
            }
        }
    }
}

@Composable
fun PipelineStep(
    title: String,
    isDone: Boolean,
    taskDetails: ActiveTaskDetails?,
    stepType: TaskType,
    enabled: Boolean = true,
    isEditable: Boolean = false,
    onClick: () -> Unit,
    onEditClick: () -> Unit = {}
) {
    val isProcessingThisStep = taskDetails?.taskType == stepType
    val progressAnimation by animateFloatAsState(
        targetValue = if (isProcessingThisStep) taskDetails.task.progress.toFloat() else 0f
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDone) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))

                if (isEditable) {
                    OutlinedButton(
                        onClick = onEditClick,
                        enabled = !isProcessingThisStep
                    ) {
                        Text("Редактор")
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Button(
                    onClick = onClick,
                    enabled = enabled && !isProcessingThisStep && !isDone
                ) {
                    Text(if (isDone) "Готово" else "Запустить")
                }
            }
            if (isProcessingThisStep) {
                Spacer(Modifier.height(12.dp))
                Column {
                    LinearProgressIndicator(
                        progress = { progressAnimation },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${taskDetails.task.message} (${(taskDetails.task.progress * 100).toInt()}%)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssetsPanel(
    modifier: Modifier = Modifier,
    assetsState: AssetsState,
    viewModel: WorkspaceViewModel
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Персонажи", "Настройки")

    Column(modifier = modifier) {
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    text = { Text(title) },
                    selected = selectedTab == index,
                    onClick = { selectedTab = index }
                )
            }
        }
        Box(modifier = Modifier.fillMaxSize().padding(top = 16.dp)) {
            when (selectedTab) {
                0 -> {
                    // Заглушка для персонажей
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Здесь будет управление персонажами.", style = MaterialTheme.typography.bodyLarge)
                    }
                }

                1 -> {
                    // НОВЫЙ КОМПОНЕНТ: Редактор Манифеста
                    ManifestEditorTab(
                        content = assetsState.manifestContent,
                        isLoading = assetsState.isManifestLoading,
                        isSaving = assetsState.isManifestSaving,
                        onLoad = { viewModel.loadManifest() },
                        onSave = { newContent -> viewModel.saveManifest(newContent) }
                    )
                }
            }
        }
    }
}


// КОМПОНЕНТ для вкладки "Настройки" (Редактор Манифеста)
@Composable
private fun ManifestEditorTab(
    content: String?,
    isLoading: Boolean,
    isSaving: Boolean,
    onLoad: () -> Unit,
    onSave: (String) -> Unit
) {
    // Загружаем манифест при первом показе
    LaunchedEffect(Unit) {
        if (content == null) {
            onLoad()
        }
    }

    var text by remember(content) { mutableStateOf(content ?: "") }

    Column(Modifier.fillMaxSize()) {
        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().weight(1f),
                label = { Text("manifest.json") },
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onSave(text) },
                enabled = !isSaving && text != content,
                modifier = Modifier.align(Alignment.End)
            ) {
                if (isSaving) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Save, contentDescription = "Сохранить")
                    Spacer(Modifier.width(8.dp))
                    Text("Сохранить")
                }
            }
        }
    }
}


@Composable
fun RowScope.VerticalDivider(
    modifier: Modifier = Modifier,
    thickness: Dp = Dp.Hairline,
    color: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
) {
    Box(
        modifier
            .fillMaxHeight()
            .width(thickness)
            .background(color = color)
    )
}

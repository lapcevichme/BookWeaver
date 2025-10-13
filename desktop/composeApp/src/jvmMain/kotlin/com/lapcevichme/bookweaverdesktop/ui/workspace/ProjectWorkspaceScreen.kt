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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lapcevichme.bookweaverdesktop.domain.model.Chapter
import com.lapcevichme.bookweaverdesktop.domain.model.ProjectDetails
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectWorkspaceScreen(
    bookName: String,
    onChapterClick: (volume: Int, chapter: Int) -> Unit,
    onEditManifestClick: (bookName: String) -> Unit,
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
                            uiState = uiState,
                            onGenerateScenario = { vol, chap -> viewModel.generateScenario(vol, chap) },
                            onSynthesizeAudio = { vol, chap -> viewModel.synthesizeAudio(vol, chap) },
                            onApplyVoiceConversion = { vol, chap -> viewModel.applyVoiceConversion(vol, chap) },
                            onAnalyzeCharacters = { viewModel.analyzeCharacters() },
                            onGenerateSummaries = { viewModel.generateSummaries() },
                            onEditScenario = { vol, chap -> onChapterClick(vol, chap) }
                        )
                        VerticalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        AssetsPanel(
                            modifier = Modifier.weight(1.5f).fillMaxHeight().padding(16.dp),
                            onEditManifestClick = { onEditManifestClick(bookName) }
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
    uiState: WorkspaceUiState,
    onGenerateScenario: (Int, Int) -> Unit,
    onSynthesizeAudio: (Int, Int) -> Unit,
    onApplyVoiceConversion: (Int, Int) -> Unit,
    onAnalyzeCharacters: () -> Unit,
    onGenerateSummaries: () -> Unit,
    onEditScenario: (Int, Int) -> Unit
) {
    val activeTask = uiState.activeTask
    val selectedChapter = uiState.selectedChapter
    val projectDetails = uiState.projectDetails!!

    // ИСПРАВЛЕНО: Простая и понятная проверка, запущена ли какая-либо задача
    val isAnyTaskRunning = activeTask != null

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Общие задачи для книги", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))

        BookwidePipelineStep(
            title = "1. Анализ персонажей",
            isDone = projectDetails.hasCharacterAnalysis,
            // ИСПРАВЛЕНО: Передаем задачу, только если она относится к этому шагу
            taskDetails = activeTask.takeIf { it?.taskType == TaskType.CHARACTER_ANALYSIS },
            stepType = TaskType.CHARACTER_ANALYSIS,
            onClick = onAnalyzeCharacters,
            // ИСПРАВЛЕНО: Кнопка активна, только если никакая другая задача не выполняется
            enabled = !isAnyTaskRunning
        )

        BookwidePipelineStep(
            title = "2. Генерация пересказов",
            isDone = projectDetails.hasSummaries,
            taskDetails = activeTask.takeIf { it?.taskType == TaskType.SUMMARY_GENERATION },
            stepType = TaskType.SUMMARY_GENERATION,
            onClick = onGenerateSummaries,
            enabled = !isAnyTaskRunning
        )

        Divider(modifier = Modifier.padding(vertical = 24.dp))

        if (selectedChapter == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Выберите главу слева", style = MaterialTheme.typography.titleLarge)
            }
        } else {
            // ИСПРАВЛЕНО: Передаем задачу, только если она относится к выбранной главе
            val currentChapterTask = activeTask.takeIf {
                it?.volumeNumber == selectedChapter.volumeNumber && it.chapterNumber == selectedChapter.chapterNumber
            }

            Text(
                "Том ${selectedChapter.volumeNumber}, Глава ${selectedChapter.chapterNumber}",
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(Modifier.height(16.dp))

            PipelineStep(
                title = "3. Генерация сценария",
                isDone = selectedChapter.hasScenario,
                taskDetails = currentChapterTask,
                stepType = TaskType.SCENARIO,
                onClick = { onGenerateScenario(selectedChapter.volumeNumber, selectedChapter.chapterNumber) },
                onEditClick = { onEditScenario(selectedChapter.volumeNumber, selectedChapter.chapterNumber) },
                isEditable = selectedChapter.hasScenario,
                enabled = !isAnyTaskRunning // Также блокируем кнопки глав, если запущена общая задача
            )

            PipelineStep(
                title = "4. Синтез аудио (TTS)",
                isDone = selectedChapter.hasAudio,
                taskDetails = currentChapterTask,
                stepType = TaskType.AUDIO,
                enabled = selectedChapter.hasScenario && !isAnyTaskRunning,
                onClick = { onSynthesizeAudio(selectedChapter.volumeNumber, selectedChapter.chapterNumber) }
            )

            PipelineStep(
                title = "5. Эмоциональная окраска (VC)",
                isDone = false, // TODO: Add hasVoiceConversion flag to model
                taskDetails = currentChapterTask,
                stepType = TaskType.VOICE_CONVERSION,
                enabled = selectedChapter.hasAudio && !isAnyTaskRunning,
                onClick = { onApplyVoiceConversion(selectedChapter.volumeNumber, selectedChapter.chapterNumber) }
            )
        }
    }
}

@Composable
fun BookwidePipelineStep(
    title: String,
    isDone: Boolean,
    taskDetails: ActiveTaskDetails?,
    stepType: TaskType,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val isProcessingThisStep = taskDetails?.taskType == stepType
    val progressAnimation by animateFloatAsState(
        targetValue = if (isProcessingThisStep) taskDetails!!.task.progress.toFloat() else 0f
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
                Button(
                    onClick = onClick,
                    // ИСПРАВЛЕНО: Используем переданный `enabled`
                    enabled = enabled && !isDone
                ) {
                    Text(if (isDone) "Готово" else "Запустить")
                }
            }
            if (isProcessingThisStep) {
                Spacer(Modifier.height(12.dp))
                Column {
                    LinearProgressIndicator(progress = { progressAnimation }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${taskDetails!!.task.message} (${(taskDetails.task.progress * 100).toInt()}%)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
        targetValue = if (isProcessingThisStep) taskDetails!!.task.progress.toFloat() else 0f
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
                        enabled = enabled // Редактировать можно всегда, если не запущена другая задача
                    ) {
                        Text("Редактор")
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Button(
                    onClick = onClick,
                    enabled = enabled && !isDone
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
                        text = "${taskDetails!!.task.message} (${(taskDetails.task.progress * 100).toInt()}%)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun AssetsPanel(
    modifier: Modifier = Modifier,
    onEditManifestClick: () -> Unit
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
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Здесь будет управление персонажами.", style = MaterialTheme.typography.bodyLarge)
                    }
                }

                1 -> {
                    Column(
                        Modifier.fillMaxSize().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
                    ) {
                        Text(
                            "Манифест проекта (manifest.json) содержит основные настройки и метаданные книги.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        Button(onClick = onEditManifestClick) {
                            Icon(Icons.Default.Edit, contentDescription = "Редактировать")
                            Spacer(Modifier.width(8.dp))
                            Text("Открыть редактор манифеста")
                        }
                    }
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


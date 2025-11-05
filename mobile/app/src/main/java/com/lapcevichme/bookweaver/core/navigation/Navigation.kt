package com.lapcevichme.bookweaver.core.navigation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lapcevichme.bookweaver.core.PlayerState
import com.lapcevichme.bookweaver.core.service.MediaPlayerService
import com.lapcevichme.bookweaver.core.ui.components.MiniPlayerBar
import com.lapcevichme.bookweaver.core.ui.theme.BookThemeWrapper
import com.lapcevichme.bookweaver.core.ui.theme.BookThemeWrapperViewModel
import com.lapcevichme.bookweaver.core.ui.theme.getLocalActivity
import com.lapcevichme.bookweaver.features.bookhub.BookHubScreen
import com.lapcevichme.bookweaver.features.bookhub.BookHubViewModel
import com.lapcevichme.bookweaver.features.bookinstall.BookInstallationViewModel
import com.lapcevichme.bookweaver.features.bookinstall.InstallBookScreen
import com.lapcevichme.bookweaver.features.chapterdetails.ChapterDetailsScreen
import com.lapcevichme.bookweaver.features.chapterdetails.ChapterDetailsViewModel
import com.lapcevichme.bookweaver.features.characterdetails.CharacterDetailsScreen
import com.lapcevichme.bookweaver.features.characters.CharactersScreen
import com.lapcevichme.bookweaver.features.library.LibraryScreen
import com.lapcevichme.bookweaver.features.library.LibraryViewModel
import com.lapcevichme.bookweaver.features.main.MainViewModel
import com.lapcevichme.bookweaver.features.main.NavigationEvent
import com.lapcevichme.bookweaver.features.main.StartupState
import com.lapcevichme.bookweaver.features.player.PlayerScreen
import com.lapcevichme.bookweaver.features.player.PlayerViewModel
import com.lapcevichme.bookweaver.features.settings.BookSettingsScreen
import com.materialkolor.DynamicMaterialExpressiveTheme
import kotlinx.coroutines.flow.collectLatest
import com.lapcevichme.bookweaver.features.library.NavigationEvent as LibraryNavigationEvent


// --- ОПРЕДЕЛЕНИЕ ВСЕХ МАРШРУТОВ ПРИЛОЖЕНИЯ ---

sealed class Screen(val route: String) {
    // --- Верхнеуровневые экраны (появляются поверх BottomNav) ---
    object OnboardingLibrary : Screen("onboarding_library")
    object InstallBook : Screen("install_book")
    object AppSettings : Screen("app_settings")
    object ChapterDetails : Screen("chapter_details") {
        const val bookIdArg = "bookId"
        const val chapterIdArg = "chapterId"
        val routeWithArgs = "$route/{$bookIdArg}/{$chapterIdArg}"
        val arguments = listOf(
            navArgument(bookIdArg) { type = NavType.StringType },
            navArgument(chapterIdArg) { type = NavType.StringType }
        )

        fun createRoute(bookId: String, chapterId: String) = "$route/$bookId/$chapterId"
    }

    object Characters : Screen("characters") {
        const val bookIdArg = "bookId"
        val routeWithArgs = "$route/{$bookIdArg}"
        val arguments = listOf(navArgument(bookIdArg) { type = NavType.StringType })
        fun createRoute(bookId: String) = "$route/$bookId"
    }

    object CharacterDetails : Screen("character_details") {
        const val bookIdArg = "bookId"
        const val characterIdArg = "characterId"
        val routeWithArgs = "$route/{$bookIdArg}/{$characterIdArg}"
        val arguments = listOf(
            navArgument(bookIdArg) { type = NavType.StringType },
            navArgument(characterIdArg) { type = NavType.StringType }
        )

        fun createRoute(bookId: String, characterId: String) = "$route/$bookId/$characterId"
    }

    object BookSettings : Screen("book_settings") {
        const val bookIdArg = "bookId"
        val routeWithArgs = "$route/{$bookIdArg}"
        val arguments = listOf(navArgument(bookIdArg) { type = NavType.StringType })
        fun createRoute(bookId: String) = "$route/$bookId"
    }


    // --- Экраны внутри BottomNav ---
    sealed class Bottom(route: String, val label: String, val icon: ImageVector) : Screen(route) {
        object BookHub : Bottom("bottom_book_hub", "Книга", Icons.Default.Book)
        object Player : Bottom("bottom_player", "Плеер", Icons.Default.PlayArrow)
        object LoreHelper : Bottom("bottom_lore_helper", "Помощник", Icons.Default.QuestionAnswer)
        object Library :
            Bottom("bottom_library", "Библиотека", Icons.AutoMirrored.Filled.LibraryBooks)
    }
}

private val bottomNavItems = listOf(
    Screen.Bottom.BookHub,
    Screen.Bottom.Player,
    Screen.Bottom.LoreHelper,
    Screen.Bottom.Library,
)

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val mainViewModel: MainViewModel = hiltViewModel()

    val context = LocalContext.current
    val activity = getLocalActivity()

    val playerViewModel: PlayerViewModel = hiltViewModel(activity)
    val playerUiState by playerViewModel.uiState.collectAsStateWithLifecycle()

    var mediaService by remember { mutableStateOf<MediaPlayerService?>(null) }

    val playerState by mediaService?.playerStateFlow
        ?.collectAsStateWithLifecycle(initialValue = PlayerState())
        ?: remember { mutableStateOf(PlayerState()) }

    // "Замыкаем цикл", передавая PayerState обратно в ViewModel !!
    // Это позволит ViewModel самой сбрасывать команду, когда она выполнится.
    LaunchedEffect(playerState) {
        playerViewModel.onPlayerStateChanged(playerState)
    }

    val serviceConnection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as MediaPlayerService.LocalBinder
                mediaService = binder.getService()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                mediaService = null
            }
        }
    }

    DisposableEffect(context) {
        val serviceIntent = Intent(context, MediaPlayerService::class.java)
        ContextCompat.startForegroundService(context, serviceIntent)
        context.bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        onDispose {
            Log.d("AppNavHost", "onDispose: Unbinding from MediaPlayerService")
            context.unbindService(serviceConnection)
        }
    }

    // "Диспетчер"
    LaunchedEffect(playerUiState, mediaService) {
        val service = mediaService ?: return@LaunchedEffect
        val command = playerUiState.loadCommand
        val chapterInfo = playerUiState.chapterInfo
        val bookId = playerUiState.bookId
        val chapterId = playerUiState.chapterId

        val currentServiceChapterId = service.playerStateFlow.value.loadedChapterId
        val isServiceEmpty = currentServiceChapterId.isEmpty()

        // Сценарий 0: Очистка
        if (playerUiState.clearService) {
            Log.d("AppNavHost_Sync", "SCENARIO 0: ClearService command received.")
            if (!isServiceEmpty) {
                service.stopAndClear()
            }
            playerViewModel.onServiceCleared()
            return@LaunchedEffect
        }

        // Сценарий 1: Активная команда
        if (command != null) {
            Log.d("AppNavHost_Sync", "SCENARIO 1: Active LoadCommand")
            if (chapterInfo == null || bookId == null || chapterId == null) {
                Log.e("AppNavHost_Sync", "LoadCommand failed: chapterInfo or IDs are null")
                return@LaunchedEffect
            }

            val isCorrectChapterLoaded = currentServiceChapterId.isNotEmpty() &&
                    currentServiceChapterId == chapterId

            if (isCorrectChapterLoaded) {
                Log.d(
                    "AppNavHost_Sync",
                    "Executing command on loaded chapter: Play=${command.playWhenReady}, Seek=${command.seekToPositionMs}"
                )
                if (command.seekToPositionMs != null) {
                    service.seekTo(command.seekToPositionMs)
                }
                if (command.playWhenReady) {
                    service.play()
                }
                // Сбрасываем команду, ТОЛЬКО если это seek/play для УЖЕ загруженной главы
                playerViewModel.onMediaSet()
            } else {
                // Новая глава. Загружаем.
                Log.d(
                    "AppNavHost_Sync",
                    "Executing command by calling setMedia: Play=${command.playWhenReady}, Seek=${command.seekToPositionMs}"
                )
                service.setMedia(
                    bookId = bookId,
                    chapterId = chapterId,
                    chapterTitle = chapterInfo.chapterTitle,
                    coverPath = chapterInfo.coverPath,
                    playWhenReady = command.playWhenReady,
                    seekToPositionMs = command.seekToPositionMs ?: chapterInfo.lastListenedPosition
                )
            }
            return@LaunchedEffect // Выходим в любом случае, если была команда
        }

        // Сценарий 2: Пассивное восстановление
        // (Выполняется, ТОЛЬКО если command == null)
        if (chapterInfo != null && bookId != null && chapterId != null) {
            Log.d("AppNavHost_Sync", "SCENARIO 2: Passive Restore Check")
            val isCorrectChapterLoaded = currentServiceChapterId.isNotEmpty() &&
                    currentServiceChapterId == chapterId

            if (!isCorrectChapterLoaded && isServiceEmpty) {
                Log.d("AppNavHost_Sync", "Triggering passive restore (Service was empty).")
                service.setMedia(
                    bookId = bookId,
                    chapterId = chapterId,
                    chapterTitle = chapterInfo.chapterTitle,
                    coverPath = chapterInfo.coverPath,
                    playWhenReady = false, // Пассивное не играет
                    seekToPositionMs = chapterInfo.lastListenedPosition
                )
            } else if (!isCorrectChapterLoaded && !isServiceEmpty) {
                Log.d("AppNavHost_Sync", "Triggering passive restore (Service has wrong chapter).")
                service.setMedia(
                    bookId = bookId,
                    chapterId = chapterId,
                    chapterTitle = chapterInfo.chapterTitle,
                    coverPath = chapterInfo.coverPath,
                    playWhenReady = false, // Пассивное не играет
                    seekToPositionMs = chapterInfo.lastListenedPosition
                )
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = "app_root"
    ) {
        composable("app_root") {
            val viewModel: MainViewModel = hiltViewModel(it)
            val startupState by viewModel.startupState.collectAsStateWithLifecycle()

            // Экран загрузки, пока ViewModel определяет состояние
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            LaunchedEffect(startupState) {
                when (startupState) {
                    StartupState.NoBooks -> {
                        navController.navigate(Screen.OnboardingLibrary.route) {
                            popUpTo("app_root") { inclusive = true }
                        }
                    }

                    StartupState.GoToLibrary -> {
                        navController.navigate("main_scaffold/${Screen.Bottom.Library.route}") {
                            popUpTo("app_root") { inclusive = true }
                        }
                    }

                    StartupState.GoToBookHub -> {
                        navController.navigate("main_scaffold/${Screen.Bottom.BookHub.route}") {
                            popUpTo("app_root") { inclusive = true }
                        }
                    }

                    StartupState.Loading -> { /* Ничего не делаем, ждем */
                    }
                }
            }
        }

        composable(
            "main_scaffold/{start_route}",
            arguments = listOf(navArgument("start_route") { type = NavType.StringType })
        ) { backStackEntry ->
            val startRoute = backStackEntry.arguments?.getString("start_route")
            checkNotNull(startRoute)

            MainScaffold(
                rootNavController = navController,
                startBottomRoute = startRoute,
                mainViewModel = mainViewModel,
                playerViewModel = playerViewModel,
                playerState = playerState,
                mediaService = mediaService
            )
        }

        composable(Screen.OnboardingLibrary.route) {
            OnboardingLibraryScreen(
                onBookInstalled = {
                    navController.navigate("main_scaffold/${Screen.Bottom.Library.route}") {
                        popUpTo(Screen.OnboardingLibrary.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.InstallBook.route) {
            val viewModel: BookInstallationViewModel = hiltViewModel()
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            InstallBookScreen(
                uiState = uiState,
                onEvent = viewModel::onEvent,
                onInstallationSuccess = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.ChapterDetails.routeWithArgs,
            arguments = Screen.ChapterDetails.arguments
        ) { backStackEntry ->
            val viewModel: ChapterDetailsViewModel = hiltViewModel(backStackEntry)
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            BookThemeWrapper {
                ChapterDetailsScreen(
                    state = uiState,
                    playerState = playerState,
                    playerViewModel = playerViewModel,
                    mainViewModel = mainViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }

        composable(
            route = Screen.Characters.routeWithArgs,
            arguments = Screen.Characters.arguments
        ) { backStackEntry ->
            BookThemeWrapper {
                CharactersScreen(
                    onCharacterClick = { characterId ->
                        val bookId =
                            backStackEntry.arguments?.getString(Screen.Characters.bookIdArg)
                        checkNotNull(bookId) { "bookId is required for CharacterDetails" }
                        navController.navigate(
                            Screen.CharacterDetails.createRoute(bookId, characterId)
                        )
                    },
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
        composable(
            route = Screen.CharacterDetails.routeWithArgs,
            arguments = Screen.CharacterDetails.arguments
        ) {
            BookThemeWrapper {
                CharacterDetailsScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }

        composable(
            route = Screen.BookSettings.routeWithArgs,
            arguments = Screen.BookSettings.arguments
        ) {
            BookThemeWrapper {
                BookSettingsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onBookDeleted = {
                        navController.navigate("main_scaffold/${Screen.Bottom.Library.route}") {
                            popUpTo("app_root") { inclusive = true }
                        }
                    }
                )
            }
        }
    }
}


@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MainScaffold(
    rootNavController: NavHostController,
    startBottomRoute: String,
    mainViewModel: MainViewModel,
    playerViewModel: PlayerViewModel,
    playerState: PlayerState,
    mediaService: MediaPlayerService?
) {
    val bottomNavController = rememberNavController()

    val activity = getLocalActivity()
    val themeViewModel: BookThemeWrapperViewModel = hiltViewModel(activity)
    val bookSeedColor by themeViewModel.themeSeedColor.collectAsStateWithLifecycle()

    val navBackStackEntry by bottomNavController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val defaultSeedColor = Color(0xFF00668B)
    val routeToCheck = currentRoute ?: startBottomRoute
    val finalSeedColor = when (routeToCheck) {
        Screen.Bottom.Library.route -> defaultSeedColor
        else -> bookSeedColor
    }

    val playerUiState by playerViewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        mainViewModel.navigationEvent.collect { event ->
            when (event) {
                is NavigationEvent.NavigateToPlayer -> {
                    bottomNavController.navigate(Screen.Bottom.Player.route) {
                        popUpTo(bottomNavController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                    }
                }
            }
        }
    }

    DynamicMaterialExpressiveTheme(
        seedColor = finalSeedColor,
        isDark = isSystemInDarkTheme(),
        animate = true,
        motionScheme = MotionScheme.expressive()
    ) {
        Scaffold(
            bottomBar = {
                Column {
                    val navBackStackEntry by bottomNavController.currentBackStackEntryAsState()
                    val currentDestination = navBackStackEntry?.destination

                    val isPlayerScreenActive =
                        currentDestination?.route == Screen.Bottom.Player.route

                    // Показываем, ТОЛЬКО если СЕРВИС что-то загрузил
                    val isSomethingLoaded =
                        playerState.loadedChapterId.isNotEmpty()

                    val showMiniPlayer = isSomethingLoaded && !isPlayerScreenActive

                    if (showMiniPlayer) {
                        MiniPlayerBar(
                            playerState = playerState,
                            chapterTitle = playerState.fileName.ifEmpty { "Аудиоплеер" },
                            bookTitle = playerUiState.chapterInfo?.bookTitle ?: "",
                            onPlayPauseClick = { mediaService?.togglePlayPause() },
                            onBarClick = {
                                bottomNavController.navigate(Screen.Bottom.Player.route) {
                                    popUpTo(bottomNavController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                }
                            }
                        )
                    }

                    NavigationBar {
                        val navBackStackEntry by bottomNavController.currentBackStackEntryAsState()
                        val currentDestination = navBackStackEntry?.destination

                        bottomNavItems.forEach { screen ->
                            NavigationBarItem(
                                icon = { Icon(screen.icon, contentDescription = screen.label) },
                                label = { Text(screen.label) },
                                selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                                onClick = {
                                    bottomNavController.navigate(screen.route) {
                                        popUpTo(bottomNavController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        if (screen.route != Screen.Bottom.Player.route) {
                                            restoreState = true
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            },
            floatingActionButton = {
                val navBackStackEntry by bottomNavController.currentBackStackEntryAsState()
                if (navBackStackEntry?.destination?.route == Screen.Bottom.Library.route) {
                    FloatingActionButton(onClick = { rootNavController.navigate(Screen.InstallBook.route) }) {
                        Icon(
                            Icons.AutoMirrored.Filled.LibraryBooks,
                            contentDescription = "Добавить книгу"
                        )
                    }
                }
            }
        ) { innerPadding ->

            NavHost(
                navController = bottomNavController,
                startDestination = startBottomRoute,
                modifier = Modifier.fillMaxSize()
            ) {
                composable(Screen.Bottom.BookHub.route) {
                    val viewModel: BookHubViewModel = hiltViewModel()
                    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

                    BookHubScreen(
                        uiState = uiState,
                        bottomContentPadding = innerPadding.calculateBottomPadding(),
                        onNavigateToCharacters = {
                            uiState.bookId?.let { bookId ->
                                rootNavController.navigate(Screen.Characters.createRoute(bookId))
                            }
                        },
                        onNavigateToSettings = { bookId ->
                            rootNavController.navigate(Screen.BookSettings.createRoute(bookId))
                        },
                        onChapterViewDetailsClick = { chapterId ->
                            uiState.bookId?.let { bookId ->
                                rootNavController.navigate(
                                    Screen.ChapterDetails.createRoute(
                                        bookId = bookId,
                                        chapterId = chapterId
                                    )
                                )
                            }
                        },
                        onChapterPlayClick = { chapterId ->
                            uiState.bookId?.let { bookId ->
                                playerViewModel.playChapter(bookId, chapterId)
                            }

                            bottomNavController.navigate(Screen.Bottom.Player.route) {
                                popUpTo(bottomNavController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                            }
                        }
                    )
                }

                composable(Screen.Bottom.Library.route) {
                    val viewModel: LibraryViewModel = hiltViewModel()
                    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

                    LaunchedEffect(Unit) {
                        viewModel.navigationEvent.collectLatest { event ->
                            when (event) {
                                is LibraryNavigationEvent.NavigateToBookHub -> {
                                    bottomNavController.navigate(Screen.Bottom.BookHub.route) {
                                        popUpTo(bottomNavController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            }
                        }
                    }


                    LibraryScreen(
                        uiState = uiState,
                        bottomContentPadding = innerPadding.calculateBottomPadding(),
                        onEvent = viewModel::onEvent,
                        onNavigateToSettings = { rootNavController.navigate(Screen.AppSettings.route) }
                    )
                }

                composable(Screen.Bottom.Player.route) {
                    PlayerScreen(
                        viewModel = playerViewModel,
                        playerState = playerState,
                        mediaService = mediaService
                    )
                }

                composable(Screen.Bottom.LoreHelper.route) {
                    Box(modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding())) {
                        LoreHelperScreenPlaceholder()
                    }
                }
            }
        }
    }
}


// Заглушки для недостающих экранов

@Composable
fun OnboardingLibraryScreen(onBookInstalled: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Button(onClick = onBookInstalled) {
            Text("Добавить первую книгу (заглушка)")
        }
    }
}

@Composable
fun LoreHelperScreenPlaceholder() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Помощник по лору", style = MaterialTheme.typography.headlineMedium)
    }
}

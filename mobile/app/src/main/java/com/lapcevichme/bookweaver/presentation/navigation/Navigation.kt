package com.lapcevichme.bookweaver.presentation.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.lapcevichme.bookweaver.presentation.ui.book.BookHubScreen
import com.lapcevichme.bookweaver.presentation.ui.book.InstallBookScreen
import com.lapcevichme.bookweaver.presentation.ui.book.bookdetails.BookDetailsViewModel
import com.lapcevichme.bookweaver.presentation.ui.book.bookinstall.BookInstallationViewModel
import com.lapcevichme.bookweaver.presentation.ui.chapterdetails.ChapterDetailsScreen
import com.lapcevichme.bookweaver.presentation.ui.chapterdetails.ChapterDetailsViewModel
import com.lapcevichme.bookweaver.presentation.ui.character.characters.CharactersScreen
import com.lapcevichme.bookweaver.presentation.ui.character.charactersdetails.CharacterDetailsScreen
import com.lapcevichme.bookweaver.presentation.ui.library.LibraryScreen
import com.lapcevichme.bookweaver.presentation.ui.library.LibraryViewModel
import com.lapcevichme.bookweaver.presentation.ui.main.MainViewModel
import com.lapcevichme.bookweaver.presentation.ui.main.NavigationEvent
import com.lapcevichme.bookweaver.presentation.ui.main.StartupState
import com.lapcevichme.bookweaver.presentation.ui.player.PlayerScreen
import com.lapcevichme.bookweaver.presentation.ui.settings.BookSettingsScreen
import kotlinx.coroutines.flow.collectLatest
import com.lapcevichme.bookweaver.presentation.ui.library.NavigationEvent as LibraryNavigationEvent


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
        object Library : Bottom("bottom_library", "Библиотека", Icons.Default.LibraryBooks)
    }
}

private val bottomNavItems = listOf(
    Screen.Bottom.BookHub,
    Screen.Bottom.Player,
    Screen.Bottom.LoreHelper,
    Screen.Bottom.Library,
)

// --- ГЛАВНЫЙ NAVHOST ПРИЛОЖЕНИЯ ---

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val mainViewModel: MainViewModel = hiltViewModel()

    NavHost(
        navController = navController,
        startDestination = "app_root" // Новый стартовый маршрут-диспетчер
    ) {
        // Маршрут-диспетчер для определения, куда направить пользователя
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
                        // Передаем маршрут библиотеки как стартовый для MainScaffold
                        navController.navigate("main_scaffold/${Screen.Bottom.Library.route}") {
                            popUpTo("app_root") { inclusive = true }
                        }
                    }

                    StartupState.GoToBookHub -> {
                        // Передаем маршрут хаба как стартовый для MainScaffold
                        navController.navigate("main_scaffold/${Screen.Bottom.BookHub.route}") {
                            popUpTo("app_root") { inclusive = true }
                        }
                    }

                    StartupState.Loading -> { /* Ничего не делаем, ждем */
                    }
                }
            }
        }

        // --- Главный экран с нижней навигацией. Теперь он принимает стартовую вкладку ---
        composable(
            "main_scaffold/{start_route}",
            arguments = listOf(navArgument("start_route") { type = NavType.StringType })
        ) { backStackEntry ->
            val startRoute = backStackEntry.arguments?.getString("start_route")
            checkNotNull(startRoute)
            MainScaffold(
                rootNavController = navController,
                startBottomRoute = startRoute,
                mainViewModel = mainViewModel
            )
        }

        // --- Экраны, которые открываются поверх всего ---
        composable(Screen.OnboardingLibrary.route) {
            OnboardingLibraryScreen(
                onBookInstalled = {
                    // После установки первой книги переходим в библиотеку
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
            ChapterDetailsScreen(
                state = uiState,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.Characters.routeWithArgs,
            arguments = Screen.Characters.arguments
        ) { backStackEntry ->
            CharactersScreen(
                onCharacterClick = { characterId ->
                    val bookId = backStackEntry.arguments?.getString(Screen.Characters.bookIdArg)
                    checkNotNull(bookId) { "bookId is required for CharacterDetails" }
                    navController.navigate(
                        Screen.CharacterDetails.createRoute(bookId, characterId)
                    )
                },
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(
            route = Screen.CharacterDetails.routeWithArgs,
            arguments = Screen.CharacterDetails.arguments
        ) {
            CharacterDetailsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.BookSettings.routeWithArgs,
            arguments = Screen.BookSettings.arguments
        ) {
            BookSettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onBookDeleted = {
                    // После удаления книги возвращаемся в библиотеку
                    navController.navigate("main_scaffold/${Screen.Bottom.Library.route}") {
                        popUpTo("app_root") { inclusive = true }
                    }
                }
            )
        }
    }
}


// SCAFFOLD С BOTTOMNAV И ВЛОЖЕННЫМ NAVHOST

@Composable
fun MainScaffold(
    rootNavController: NavHostController,
    startBottomRoute: String,
    mainViewModel: MainViewModel
) {
    val bottomNavController = rememberNavController()

    LaunchedEffect(Unit) {
        mainViewModel.navigationEvent.collect { event ->
            when (event) {
                is NavigationEvent.NavigateToPlayer -> {
                    bottomNavController.navigate(Screen.Bottom.Player.route) {
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

    Scaffold(
        bottomBar = {
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
                                restoreState = true
                            }
                        }
                    )
                }
            }
        },
        floatingActionButton = {
            val navBackStackEntry by bottomNavController.currentBackStackEntryAsState()
            if (navBackStackEntry?.destination?.route == Screen.Bottom.Library.route) {
                FloatingActionButton(onClick = { rootNavController.navigate(Screen.InstallBook.route) }) {
                    Icon(Icons.Default.LibraryBooks, contentDescription = "Добавить книгу")
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = bottomNavController,
            startDestination = startBottomRoute,
            modifier = Modifier
        ) {
            composable(Screen.Bottom.BookHub.route) {
                val viewModel: BookDetailsViewModel = hiltViewModel()
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()

                BookHubScreen(
                    uiState = uiState,
                    onNavigateToCharacters = {
                        uiState.bookId?.let { bookId ->
                            rootNavController.navigate(Screen.Characters.createRoute(bookId))
                        }
                    },
                    onNavigateToSettings = { bookId ->
                        rootNavController.navigate(Screen.BookSettings.createRoute(bookId))
                    },
                    // Этот коллбэк открывает ДЕТАЛИ главы
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
                    // Этот коллбэк ЗАПУСКАЕТ главу и переходит в плеер
                    onChapterPlayClick = { chapterId ->
                        viewModel.onPlayChapter(chapterId)

                        bottomNavController.navigate(Screen.Bottom.Player.route) {
                            popUpTo(bottomNavController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
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
                    onEvent = viewModel::onEvent,
                    onNavigateToSettings = { rootNavController.navigate(Screen.AppSettings.route) }
                )
            }

            composable(Screen.Bottom.Player.route) {
                PlayerScreen()
            }

            composable(Screen.Bottom.LoreHelper.route) { LoreHelperScreenPlaceholder() }
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

package com.lapcevichme.bookweaver.core.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.lapcevichme.bookweaver.core.PlayerState
import com.lapcevichme.bookweaver.core.service.MediaPlayerService
import com.lapcevichme.bookweaver.core.ui.theme.BookThemeWrapper
import com.lapcevichme.bookweaver.features.bookhub.BookHubScreen
import com.lapcevichme.bookweaver.features.bookhub.BookHubViewModel
import com.lapcevichme.bookweaver.features.bookinstall.BookInstallationViewModel
import com.lapcevichme.bookweaver.features.bookinstall.InstallBookScreen
import com.lapcevichme.bookweaver.features.chapterdetails.ChapterDetailsScreen
import com.lapcevichme.bookweaver.features.chapterdetails.ChapterDetailsViewModel
import com.lapcevichme.bookweaver.features.characterdetails.CharacterDetailsScreen
import com.lapcevichme.bookweaver.features.characters.CharactersScreen
import com.lapcevichme.bookweaver.features.connection.ConnectionScreen
import com.lapcevichme.bookweaver.features.library.LibraryScreen
import com.lapcevichme.bookweaver.features.library.LibraryViewModel
import com.lapcevichme.bookweaver.features.main.MainViewModel
import com.lapcevichme.bookweaver.features.main.StartupState
import com.lapcevichme.bookweaver.features.onboarding.OnboardingLibraryScreen
import com.lapcevichme.bookweaver.features.player.PlayerScreen
import com.lapcevichme.bookweaver.features.player.PlayerViewModel
import com.lapcevichme.bookweaver.features.settings.app.AppSettingsScreen
import com.lapcevichme.bookweaver.features.settings.book.BookSettingsScreen
import com.materialkolor.DynamicMaterialExpressiveTheme
import kotlinx.coroutines.flow.collectLatest
import com.lapcevichme.bookweaver.features.library.NavigationEvent as LibraryNavigationEvent

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DefaultAppTheme(isDark: Boolean, seedColor: Color, content: @Composable () -> Unit) {
    DynamicMaterialExpressiveTheme(
        seedColor = seedColor,
        isDark = isDark,
        animate = false,
        motionScheme = MotionScheme.expressive(),
        content = content
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun NavGraphBuilder.rootNavigationGraph(navController: NavController) {
    composable("app_root") {
        val viewModel: MainViewModel = hiltViewModel(it)
        val startupState by viewModel.startupState.collectAsStateWithLifecycle()

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LoadingIndicator()
        }

        LaunchedEffect(startupState) {
            when (startupState) {
                StartupState.NoBooks -> navController.navigate(Screen.OnboardingLibrary.route) {
                    popUpTo("app_root") { inclusive = true }
                }
                StartupState.GoToLibrary -> navController.navigate("main_scaffold/${Screen.Bottom.Library.route}") {
                    popUpTo("app_root") { inclusive = true }
                }
                StartupState.GoToBookHub -> navController.navigate("main_scaffold/${Screen.Bottom.BookHub.route}") {
                    popUpTo("app_root") { inclusive = true }
                }
                StartupState.Loading -> { /* Ждем */ }
            }
        }
    }
}

fun NavGraphBuilder.onboardingGraph(navController: NavController, isDark: Boolean, seedColor: Color) {
    composable(Screen.OnboardingLibrary.route) {
        DefaultAppTheme(isDark, seedColor) {
            OnboardingLibraryScreen(
                onNavigateToInstall = { navController.navigate(Screen.InstallBook.route) }
            )
        }
    }

    composable(Screen.InstallBook.route) {
        val viewModel: BookInstallationViewModel = hiltViewModel()
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()
        DefaultAppTheme(isDark, seedColor) {
            InstallBookScreen(
                uiState = uiState,
                onEvent = viewModel::onEvent,
                onNavigateBack = { navController.popBackStack() },
                onInstallationSuccess = {
                    navController.navigate("main_scaffold/${Screen.Bottom.Library.route}") {
                        popUpTo(navController.graph.id) { inclusive = true }
                    }
                }
            )
        }
    }
}

fun NavGraphBuilder.settingsGraph(navController: NavController, isDark: Boolean, seedColor: Color) {
    composable(Screen.AppSettings.route) {
        DefaultAppTheme(isDark, seedColor) {
            AppSettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToConnection = { navController.navigate(Screen.Connection.route) }
            )
        }
    }

    composable(Screen.Connection.route) {
        DefaultAppTheme(isDark, seedColor) {
            ConnectionScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}

fun NavGraphBuilder.bookGraph(navController: NavController, playerViewModel: PlayerViewModel, mainViewModel: MainViewModel, playerState: PlayerState, isDark: Boolean) {
    composable(route = Screen.ChapterDetails.routeWithArgs, arguments = Screen.ChapterDetails.arguments) { backStackEntry ->
        val viewModel: ChapterDetailsViewModel = hiltViewModel(backStackEntry)
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()

        BookThemeWrapper(isDark = isDark) {
            ChapterDetailsScreen(
                state = uiState,
                playerState = playerState,
                playerViewModel = playerViewModel,
                mainViewModel = mainViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }

    composable(route = Screen.Characters.routeWithArgs, arguments = Screen.Characters.arguments) { backStackEntry ->
        BookThemeWrapper(isDark = isDark) {
            CharactersScreen(
                onCharacterClick = { characterId ->
                    val bookId = backStackEntry.arguments?.getString(Screen.Characters.bookIdArg)
                    checkNotNull(bookId)
                    navController.navigate(Screen.CharacterDetails.createRoute(bookId, characterId))
                },
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }

    composable(route = Screen.CharacterDetails.routeWithArgs, arguments = Screen.CharacterDetails.arguments) {
        BookThemeWrapper(isDark = isDark) {
            CharacterDetailsScreen(onNavigateBack = { navController.popBackStack() })
        }
    }

    composable(route = Screen.BookSettings.routeWithArgs, arguments = Screen.BookSettings.arguments) {
        BookThemeWrapper(isDark = isDark) {
            BookSettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onBookDeleted = {
                    navController.navigate("main_scaffold/${Screen.Bottom.Library.route}") {
                        popUpTo(navController.graph.id)
                    }
                }
            )
        }
    }
}

fun NavGraphBuilder.bottomNavGraph(
    bottomNavController: NavHostController,
    rootNavController: NavHostController,
    innerPadding: PaddingValues,
    playerViewModel: PlayerViewModel,
    playerState: PlayerState,
    mediaService: MediaPlayerService?
) {
    composable(Screen.Bottom.BookHub.route) {
        val viewModel: BookHubViewModel = hiltViewModel()
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()

        BookHubScreen(
            uiState = uiState,
            bottomContentPadding = innerPadding.calculateBottomPadding(),
            onNavigateToCharacters = { uiState.bookId?.let { rootNavController.navigate(Screen.Characters.createRoute(it)) } },
            onNavigateToSettings = { bookId -> rootNavController.navigate(Screen.BookSettings.createRoute(bookId)) },
            onChapterViewDetailsClick = { chapterId ->
                uiState.bookId?.let { rootNavController.navigate(Screen.ChapterDetails.createRoute(it, chapterId)) }
            },
            onChapterPlayClick = { chapterId ->
                uiState.bookId?.let { playerViewModel.playChapter(it, chapterId) }
                bottomNavController.navigate(Screen.Bottom.Player.route) {
                    popUpTo(bottomNavController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                }
            },
            onEvent = viewModel::onEvent
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
                                inclusive = true
                                saveState = true
                            }
                            launchSingleTop = true
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
        PlayerScreen(viewModel = playerViewModel, playerState = playerState, mediaService = mediaService)
    }

    composable(Screen.Bottom.LoreHelper.route) {
        Box(modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding())) {
            LoreHelperScreenPlaceholder()
        }
    }
}
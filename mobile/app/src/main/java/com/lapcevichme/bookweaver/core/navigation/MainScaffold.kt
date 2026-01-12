package com.lapcevichme.bookweaver.core.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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
import androidx.compose.ui.graphics.Color
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lapcevichme.bookweaver.core.PlayerState
import com.lapcevichme.bookweaver.core.service.MediaPlayerService
import com.lapcevichme.bookweaver.core.ui.components.MiniPlayerBar
import com.lapcevichme.bookweaver.core.ui.theme.BookThemeWrapperViewModel
import com.lapcevichme.bookweaver.core.ui.theme.getLocalActivity
import com.lapcevichme.bookweaver.features.main.MainViewModel
import com.lapcevichme.bookweaver.features.main.NavigationEvent
import com.lapcevichme.bookweaver.features.player.PlayerViewModel

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MainScaffold(
    rootNavController: NavHostController,
    startBottomRoute: String,
    mainViewModel: MainViewModel,
    playerViewModel: PlayerViewModel,
    playerState: PlayerState,
    mediaService: MediaPlayerService?,
    isDark: Boolean,
    defaultSeedColor: Color
) {
    val bottomNavController = rememberNavController()
    val activity = getLocalActivity()
    val themeViewModel: BookThemeWrapperViewModel = hiltViewModel(activity)
    val bookSeedColor by themeViewModel.themeSeedColor.collectAsStateWithLifecycle()

    val navBackStackEntry by bottomNavController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
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
                        popUpTo(bottomNavController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                    }
                }
            }
        }
    }

    DefaultAppTheme(isDark = isDark, seedColor = finalSeedColor) {
        Scaffold(
            bottomBar = {
                Column {
                    val currentDestination = navBackStackEntry?.destination
                    val isPlayerScreenActive = currentDestination?.route == Screen.Bottom.Player.route
                    val isSomethingLoaded = playerState.loadedChapterId.isNotEmpty()
                    val showMiniPlayer = isSomethingLoaded && !isPlayerScreenActive

                    if (showMiniPlayer) {
                        MiniPlayerBar(
                            playerState = playerState,
                            chapterTitle = playerState.fileName.ifEmpty { "Аудиоплеер" },
                            bookTitle = playerUiState.chapterInfo?.bookTitle ?: "",
                            onPlayPauseClick = { mediaService?.togglePlayPause() },
                            onBarClick = {
                                bottomNavController.navigate(Screen.Bottom.Player.route) {
                                    popUpTo(bottomNavController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                }
                            }
                        )
                    }

                    NavigationBar {
                        bottomNavItems.forEach { screen ->
                            NavigationBarItem(
                                icon = { Icon(screen.icon, contentDescription = screen.label) },
                                label = { Text(screen.label) },
                                selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                                onClick = {
                                    bottomNavController.navigate(screen.route) {
                                        popUpTo(bottomNavController.graph.findStartDestination().id) { saveState = true }
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
                if (navBackStackEntry?.destination?.route == Screen.Bottom.Library.route) {
                    FloatingActionButton(onClick = { rootNavController.navigate(Screen.InstallBook.route) }) {
                        Icon(Icons.Default.Add, contentDescription = "Добавить книгу")
                    }
                }
            }
        ) { innerPadding ->
            NavHost(
                navController = bottomNavController,
                startDestination = startBottomRoute,
                modifier = Modifier.fillMaxSize()
            ) {
                bottomNavGraph(
                    bottomNavController = bottomNavController,
                    rootNavController = rootNavController,
                    innerPadding = innerPadding,
                    playerViewModel = playerViewModel,
                    playerState = playerState,
                    mediaService = mediaService
                )
            }
        }
    }
}

@Composable
fun LoreHelperScreenPlaceholder() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Помощник по лору", style = MaterialTheme.typography.headlineMedium)
    }
}
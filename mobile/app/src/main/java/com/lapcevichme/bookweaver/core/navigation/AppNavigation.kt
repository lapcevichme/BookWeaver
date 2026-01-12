package com.lapcevichme.bookweaver.core.navigation

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lapcevichme.bookweaver.core.PlayerState
import com.lapcevichme.bookweaver.core.ui.theme.getLocalActivity
import com.lapcevichme.bookweaver.domain.model.ThemeSetting
import com.lapcevichme.bookweaver.features.main.MainViewModel
import com.lapcevichme.bookweaver.features.player.PlayerViewModel

@Composable
private fun isDarkTheme(themeSetting: ThemeSetting): Boolean = when (themeSetting) {
    ThemeSetting.LIGHT -> false
    ThemeSetting.DARK -> true
    ThemeSetting.SYSTEM -> isSystemInDarkTheme()
}

@Composable
fun AppNavHost(themeSetting: ThemeSetting) {
    val navController = rememberNavController()
    val isDark = isDarkTheme(themeSetting)
    val defaultSeedColor = Color(0xFF00668B)

    val mainViewModel: MainViewModel = hiltViewModel()
    val activity = getLocalActivity()

    val playerViewModel: PlayerViewModel = hiltViewModel(activity)
    val playerUiState by playerViewModel.uiState.collectAsStateWithLifecycle()

    val mediaService = rememberMediaPlayerService()

    val playerState by mediaService?.playerStateFlow
        ?.collectAsStateWithLifecycle(initialValue = PlayerState())
        ?: remember { mutableStateOf(PlayerState()) }

    LaunchedEffect(playerState) {
        playerViewModel.onPlayerStateChanged(playerState)
    }

    MediaPlayerSyncEffect(playerViewModel, mediaService, playerUiState)

    NavHost(
        navController = navController,
        startDestination = "app_root"
    ) {
        rootNavigationGraph(navController)
        onboardingGraph(navController, isDark, defaultSeedColor)
        settingsGraph(navController, isDark, defaultSeedColor)
        bookGraph(navController, playerViewModel, mainViewModel, playerState, isDark)

        composable(
            route = "main_scaffold/{start_route}",
            arguments = listOf(navArgument("start_route") { type = NavType.StringType })
        ) { backStackEntry ->
            val startRoute = backStackEntry.arguments?.getString("start_route") ?: Screen.Bottom.BookHub.route

            MainScaffold(
                rootNavController = navController,
                startBottomRoute = startRoute,
                mainViewModel = mainViewModel,
                playerViewModel = playerViewModel,
                playerState = playerState,
                mediaService = mediaService,
                isDark = isDark,
                defaultSeedColor = defaultSeedColor
            )
        }
    }
}
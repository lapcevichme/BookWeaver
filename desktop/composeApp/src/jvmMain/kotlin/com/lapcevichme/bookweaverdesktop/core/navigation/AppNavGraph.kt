package com.lapcevichme.bookweaverdesktop.core.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.lapcevichme.bookweaverdesktop.ui.editor.manifest.ManifestEditorScreen
import com.lapcevichme.bookweaverdesktop.ui.settings.SettingsAndAssetsScreen
import com.lapcevichme.bookweaverdesktop.ui.dashboard.ProjectDashboardScreen
import com.lapcevichme.bookweaverdesktop.ui.editor.scenario.ScenarioEditorScreen
import com.lapcevichme.bookweaverdesktop.ui.workspace.ProjectWorkspaceScreen
import kotlinx.serialization.Serializable

/**
 * Типобезопасные маршруты для навигации в приложении BookWeaver.
 */
sealed interface BookWeaverRoute {
    @Serializable
    data object ProjectDashboard : BookWeaverRoute

    @Serializable
    data class ProjectWorkspace(val bookName: String) : BookWeaverRoute

    @Serializable
    data class ScenarioEditor(val bookName: String, val volume: Int, val chapter: Int) : BookWeaverRoute

    // НОВЫЙ МАРШРУТ
    @Serializable
    data class ManifestEditor(val bookName: String) : BookWeaverRoute

    @Serializable
    data object SettingsAndAssets : BookWeaverRoute
}


/**
 * Главный контейнер навигации NavHost для приложения BookWeaver.
 */
@Composable
fun BookWeaverNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    startDestination: BookWeaverRoute = BookWeaverRoute.ProjectDashboard,
    window: ComposeWindow
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        composable<BookWeaverRoute.ProjectDashboard> {
            ProjectDashboardScreen(
                onProjectClick = { bookName -> navController.navigate(BookWeaverRoute.ProjectWorkspace(bookName)) },
                onSettingsClick = { navController.navigate(BookWeaverRoute.SettingsAndAssets) },
                window = window
            )
        }

        composable<BookWeaverRoute.ProjectWorkspace> { backStackEntry ->
            val route: BookWeaverRoute.ProjectWorkspace = backStackEntry.toRoute()
            ProjectWorkspaceScreen(
                bookName = route.bookName,
                onChapterClick = { volume, chapter ->
                    navController.navigate(BookWeaverRoute.ScenarioEditor(route.bookName, volume, chapter))
                },
                // ИЗМЕНЕНО: Добавляем обработчик для перехода к редактору манифеста
                onEditManifestClick = { bookName ->
                    navController.navigate(BookWeaverRoute.ManifestEditor(bookName))
                },
                onBackClick = { navController.popBackStack() }
            )
        }

        composable<BookWeaverRoute.ScenarioEditor> { backStackEntry ->
            val route: BookWeaverRoute.ScenarioEditor = backStackEntry.toRoute()
            ScenarioEditorScreen(
                bookName = route.bookName,
                volume = route.volume,
                chapter = route.chapter,
                onBackClick = { navController.popBackStack() }
            )
        }

        // НОВЫЙ ЭКРАН В ГРАФЕ
        composable<BookWeaverRoute.ManifestEditor> { backStackEntry ->
            val route: BookWeaverRoute.ManifestEditor = backStackEntry.toRoute()
            ManifestEditorScreen(
                bookName = route.bookName,
                onBackClick = { navController.popBackStack() }
            )
        }

        composable<BookWeaverRoute.SettingsAndAssets> {
            SettingsAndAssetsScreen(
                onBackClick = { navController.popBackStack() }
            )
        }
    }
}

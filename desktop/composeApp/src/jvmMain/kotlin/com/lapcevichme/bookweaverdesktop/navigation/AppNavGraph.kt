package com.lapcevichme.bookweaverdesktop.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.lapcevichme.bookweaverdesktop.ui.ProjectDashboardScreen
import com.lapcevichme.bookweaverdesktop.ui.ProjectWorkspaceScreen
import com.lapcevichme.bookweaverdesktop.ui.ScenarioEditorScreen
import com.lapcevichme.bookweaverdesktop.ui.SettingsAndAssetsScreen
import kotlinx.serialization.Serializable

/**
 * Типобезопасные маршруты для навигации в приложении BookWeaver.
 * Используем @Serializable для безопасной передачи аргументов между экранами.
 *
 * Экраны на основе вашего User Flow:
 * 1. Панель проектов (Project Dashboard)
 * 2. Рабочее пространство проекта (Project Workspace)
 * 3. Редактор сценария (Scenario Editor)
 * 4. Настройки и Ресурсы (Settings & Assets)
 */

sealed interface BookWeaverRoute {
    // Экран 1: Панель проектов (Project Dashboard)
    @Serializable
    data object ProjectDashboard : BookWeaverRoute

    // Экран 2: Рабочее пространство проекта (Project Workspace)
    // Требует названия книги для загрузки данных
    @Serializable
    data class ProjectWorkspace(val bookName: String) : BookWeaverRoute

    // Экран 3: Редактор сценария (Scenario Editor)
    // Требует названия книги, тома и номера главы
    @Serializable
    data class ScenarioEditor(
        val bookName: String,
        val volume: Int,
        val chapter: Int
    ) : BookWeaverRoute

    // Экран 4: Настройки и Ресурсы (Settings & Assets)
    @Serializable
    data object SettingsAndAssets : BookWeaverRoute

    // Дополнительные экраны
    @Serializable
    data class ManifestEditor(val bookName: String) : BookWeaverRoute

    @Serializable
    data class CharacterArchiveEditor(val bookName: String) : BookWeaverRoute
}


/**
 * Главный контейнер навигации NavHost для приложения BookWeaver.
 */
@Composable
fun BookWeaverNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    startDestination: BookWeaverRoute = BookWeaverRoute.ProjectDashboard
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        // --- ЭКРАН 1: Панель проектов (Project Dashboard) ---
        composable<BookWeaverRoute.ProjectDashboard> {
            // Вставьте здесь Composable для Панели проектов
            ProjectDashboardScreen(
                onProjectClick = { bookName ->
                    // Навигация на Экран 2 (Рабочее пространство)
                    navController.navigate(BookWeaverRoute.ProjectWorkspace(bookName))
                },
                onSettingsClick = {
                    // Навигация на Экран 4 (Настройки)
                    navController.navigate(BookWeaverRoute.SettingsAndAssets)
                }
            )
        }

        // --- ЭКРАН 2: Рабочее пространство проекта (Project Workspace) ---
        composable<BookWeaverRoute.ProjectWorkspace> { backStackEntry ->
            // Получаем аргументы маршрута (bookName)
            val route: BookWeaverRoute.ProjectWorkspace = backStackEntry.toRoute()

            ProjectWorkspaceScreen(
                bookName = route.bookName,
                onChapterClick = { volume, chapter ->
                    // Навигация на Экран 3 (Редактор сценария)
                    navController.navigate(
                        BookWeaverRoute.ScenarioEditor(
                            bookName = route.bookName,
                            volume = volume,
                            chapter = chapter
                        )
                    )
                },
//                onManifestEditorClick = {
//                    navController.navigate(BookWeaverRoute.ManifestEditor(route.bookName))
//                },
                onBackClick = {
                    navController.popBackStack()
                }
            )
        }

        // --- ЭКРАН 3: Редактор сценария (Scenario Editor) ---
        composable<BookWeaverRoute.ScenarioEditor> { backStackEntry ->
            // Получаем аргументы маршрута (bookName, volume, chapter)
            val route: BookWeaverRoute.ScenarioEditor = backStackEntry.toRoute()

            ScenarioEditorScreen(
                bookName = route.bookName,
                volume = route.volume,
                chapter = route.chapter,
                onBackClick = {
                    navController.popBackStack()
                }
            )
        }

        // --- ЭКРАН 4: Настройки и Ресурсы (Settings & Assets) ---
        composable<BookWeaverRoute.SettingsAndAssets> {
            SettingsAndAssetsScreen(
                onBackClick = {
                    navController.popBackStack()
                }
            )
        }

        // --- ДОПОЛНИТЕЛЬНЫЙ ЭКРАН: Редактор Манифеста ---
        composable<BookWeaverRoute.ManifestEditor> { backStackEntry ->
            val route: BookWeaverRoute.ManifestEditor = backStackEntry.toRoute()

            ManifestEditorScreen(
                bookName = route.bookName,
                onBackClick = {
                    navController.popBackStack()
                }
            )
        }

        // --- ДОПОЛНИТЕЛЬНЫЙ ЭКРАН: Редактор Архива Персонажей ---
        composable<BookWeaverRoute.CharacterArchiveEditor> { backStackEntry ->
            val route: BookWeaverRoute.CharacterArchiveEditor = backStackEntry.toRoute()

            CharacterArchiveEditorScreen(
                bookName = route.bookName,
                onBackClick = {
                    navController.popBackStack()
                }
            )
        }
    }
}

@Composable
fun ManifestEditorScreen(bookName: String, onBackClick: () -> Unit) {
    // UI для редактирования manifest.json
}

@Composable
fun CharacterArchiveEditorScreen(bookName: String, onBackClick: () -> Unit) {
    // UI для редактирования character_archive.json
}

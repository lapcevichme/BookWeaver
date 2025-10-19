package com.lapcevichme.bookweaver.presentation.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lapcevichme.bookweaver.presentation.charactersdetails.CharacterDetailsScreen
import com.lapcevichme.bookweaver.presentation.ui.bookinstall.BookInstallationScreen
import com.lapcevichme.bookweaver.presentation.ui.connection.ConnectionScreen
import com.lapcevichme.bookweaver.presentation.ui.details.ChapterDetailsScreen
import com.lapcevichme.bookweaver.presentation.ui.library.LibraryScreen
import com.lapcevichme.bookweaver.presentation.ui.main.MainScreen
import com.lapcevichme.bookweaver.presentation.ui.settings.BookSettingsScreen


sealed class Screen(val route: String) {
    object Library : Screen("library")
    object Main : Screen("main/{bookId}") {
        fun createRoute(bookId: String) = "main/$bookId"
    }
    object BookSettings : Screen("book_settings/{bookId}") {
        fun createRoute(bookId: String) = "book_settings/$bookId"
    }
    object ChapterDetails : Screen("chapter_details/{bookId}/{chapterId}") {
        fun createRoute(bookId: String, chapterId: String) = "chapter_details/$bookId/$chapterId"
    }
    // Новый маршрут для деталей персонажа
    object CharacterDetails : Screen("character_details/{bookId}/{characterId}") {
        fun createRoute(bookId: String, characterId: String) = "character_details/$bookId/$characterId"
    }
    object Connection : Screen("connection")
    object InstallBook : Screen("install_book")
}

@Composable
fun AppNavHost(onScanQrClick: () -> Unit) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Screen.Library.route) {
        composable(Screen.Library.route) {
            LibraryScreen(
                onBookClick = { bookId -> navController.navigate(Screen.Main.createRoute(bookId)) },
                onInstallClick = { navController.navigate(Screen.InstallBook.route) }
            )
        }
        composable(
            route = Screen.Main.route,
            arguments = listOf(navArgument("bookId") { type = NavType.StringType })
        ) { backStackEntry ->
            val bookId = backStackEntry.arguments?.getString("bookId")
            if (bookId != null) {
                MainScreen(
                    bookId = bookId,
                    onSettingsClick = { navController.navigate(Screen.BookSettings.createRoute(bookId)) },
                    onChapterClick = { _, chapterId -> navController.navigate(Screen.ChapterDetails.createRoute(bookId, chapterId)) },
                    // Добавляем новый коллбэк
                    onCharacterClick = { _, characterId -> navController.navigate(Screen.CharacterDetails.createRoute(bookId, characterId)) },
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
        // ... другие composable ...

        // Добавляем новый экран в граф
        composable(
            route = Screen.CharacterDetails.route,
            arguments = listOf(
                navArgument("bookId") { type = NavType.StringType },
                navArgument("characterId") { type = NavType.StringType }
            )
        ) {
            CharacterDetailsScreen(onNavigateBack = { navController.popBackStack() })
        }

        // --- Существующие composable без изменений ---
        composable(
            route = Screen.BookSettings.route,
            arguments = listOf(navArgument("bookId") { type = NavType.StringType })
        ) {
            BookSettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onBookDeleted = {
                    navController.popBackStack(Screen.Library.route, inclusive = false)
                }
            )
        }
        composable(
            route = Screen.ChapterDetails.route,
            arguments = listOf(
                navArgument("bookId") { type = NavType.StringType },
                navArgument("chapterId") { type = NavType.StringType }
            )
        ) {
            ChapterDetailsScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(Screen.Connection.route) { ConnectionScreen(onScanQrClick = onScanQrClick) }
        composable(Screen.InstallBook.route) {
            BookInstallationScreen(onInstallationSuccess = { navController.popBackStack() })
        }
    }
}
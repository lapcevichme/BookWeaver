package com.lapcevichme.bookweaver.presentation.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lapcevichme.bookweaver.presentation.ui.bookinstall.BookInstallationScreen
import com.lapcevichme.bookweaver.presentation.ui.connection.ConnectionScreen
import com.lapcevichme.bookweaver.presentation.ui.details.ChapterDetailsScreen
import com.lapcevichme.bookweaver.presentation.ui.library.LibraryScreen
import com.lapcevichme.bookweaver.presentation.ui.main.MainScreen
import com.lapcevichme.bookweaver.presentation.ui.settings.BookSettingsScreen

sealed class Screen(val route: String) {
    object Library : Screen("library")

    // ИЗМЕНЕНО: BookDetails теперь MainScreen
    object Main : Screen("main/{bookId}") {
        fun createRoute(bookId: String) = "main/$bookId"
    }

    object BookSettings : Screen("book_settings/{bookId}") {
        fun createRoute(bookId: String) = "book_settings/$bookId"
    }

    object ChapterDetails : Screen("chapter_details/{bookId}/{chapterId}") {
        fun createRoute(bookId: String, chapterId: String) = "chapter_details/$bookId/$chapterId"
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
        // ИЗМЕНЕНО: BookDetails заменен на MainScreen
        composable(
            route = Screen.Main.route,
            arguments = listOf(navArgument("bookId") { type = NavType.StringType })
        ) { backStackEntry ->
            val bookId = backStackEntry.arguments?.getString("bookId")
            if (bookId != null) {
                MainScreen(
                    bookId = bookId,
                    onSettingsClick = {
                        navController.navigate(
                            Screen.BookSettings.createRoute(
                                bookId
                            )
                        )
                    },
                    onChapterClick = { _, chapterId ->
                        navController.navigate(
                            Screen.ChapterDetails.createRoute(
                                bookId,
                                chapterId
                            )
                        )
                    },
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
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


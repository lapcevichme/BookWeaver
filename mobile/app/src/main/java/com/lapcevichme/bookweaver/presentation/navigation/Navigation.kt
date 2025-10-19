package com.lapcevichme.bookweaver.presentation.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lapcevichme.bookweaver.presentation.ui.bookdetails.BookDetailsScreen
import com.lapcevichme.bookweaver.presentation.ui.bookinstall.BookInstallationScreen
import com.lapcevichme.bookweaver.presentation.ui.connection.ConnectionScreen
import com.lapcevichme.bookweaver.presentation.ui.library.LibraryScreen
import com.lapcevichme.bookweaver.presentation.ui.settings.BookSettingsScreen

sealed class Screen(val route: String) {
    object Library : Screen("library")
    object BookDetails : Screen("book_details/{bookId}") {
        fun createRoute(bookId: String) = "book_details/$bookId"
    }
    object Connection : Screen("connection")
    object InstallBook : Screen("install_book")
    object BookSettings : Screen("book_settings/{bookId}") { // Новый маршрут
        fun createRoute(bookId: String) = "book_settings/$bookId"
    }
}

@Composable
fun AppNavHost(onScanQrClick: () -> Unit) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Screen.Library.route) {
        composable(Screen.Library.route) {
            LibraryScreen(
                onBookClick = { bookId -> navController.navigate(Screen.BookDetails.createRoute(bookId)) },
                onInstallClick = { navController.navigate(Screen.InstallBook.route) }
            )
        }
        composable(Screen.BookDetails.route) {
            BookDetailsScreen(
                // Добавляем переход на экран настроек
                onSettingsClick = { bookId -> navController.navigate(Screen.BookSettings.createRoute(bookId)) },
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(Screen.BookSettings.route) { // Новый экран в графе
            BookSettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onBookDeleted = {
                    // После удаления возвращаемся в библиотеку, очистив backstack
                    navController.popBackStack(Screen.Library.route, inclusive = false)
                }
            )
        }
        composable(Screen.Connection.route) { ConnectionScreen(onScanQrClick = onScanQrClick) }
        composable(Screen.InstallBook.route) {
            BookInstallationScreen(onInstallationSuccess = { navController.popBackStack() })
        }
    }
}


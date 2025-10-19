package com.lapcevichme.bookweaver.presentation.ui.main

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Book
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.lapcevichme.bookweaver.presentation.characters.CharactersScreen
import com.lapcevichme.bookweaver.presentation.ui.bookdetails.BookDetailsScreen

// Маршруты теперь включают bookId, чтобы ViewModel мог его получить
sealed class BookScreen(val route: String, val label: String, val icon: ImageVector) {
    object Chapters : BookScreen("book_chapters/{bookId}", "Главы", Icons.Default.Book) {
        fun createRoute(bookId: String) = "book_chapters/$bookId"
    }
    object Characters : BookScreen("book_characters/{bookId}", "Персонажи", Icons.Default.AccountCircle) {
        fun createRoute(bookId: String) = "book_characters/$bookId"
    }
}

private val bookScreens = listOf(BookScreen.Chapters, BookScreen.Characters)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    bookId: String,
    onSettingsClick: (String) -> Unit,
    onChapterClick: (String, String) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val navController = rememberNavController()

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                bookScreens.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = null) },
                        label = { Text(screen.label) },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            val route = when (screen) {
                                is BookScreen.Chapters -> screen.createRoute(bookId)
                                is BookScreen.Characters -> screen.createRoute(bookId)
                            }
                            navController.navigate(route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        // ИСПРАВЛЕНО: Мы создаем новые отступы, используя только нижний отступ
        // от внешнего Scaffold. Это позволяет TopAppBar на дочерних экранах
        // самому управлять отступом от статус-бара, избегая его дублирования.
        val newPadding = PaddingValues(bottom = innerPadding.calculateBottomPadding())

        NavHost(
            navController = navController,
            startDestination = BookScreen.Chapters.createRoute(bookId),
            modifier = Modifier.padding(newPadding)
        ) {
            composable(
                route = BookScreen.Chapters.route,
                arguments = listOf(navArgument("bookId") { type = NavType.StringType })
            ) {
                BookDetailsScreen(
                    onSettingsClick = { onSettingsClick(bookId) },
                    onChapterClick = { _, chapterId -> onChapterClick(bookId, chapterId) },
                    onNavigateBack = onNavigateBack
                )
            }
            composable(
                route = BookScreen.Characters.route,
                arguments = listOf(navArgument("bookId") { type = NavType.StringType })
            ) {
                CharactersScreen()
            }
        }
    }
}


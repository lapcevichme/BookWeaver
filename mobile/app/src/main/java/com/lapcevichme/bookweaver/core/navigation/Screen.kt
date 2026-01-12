package com.lapcevichme.bookweaver.core.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavType
import androidx.navigation.navArgument

sealed class Screen(val route: String) {
    object OnboardingLibrary : Screen("onboarding_library")
    object InstallBook : Screen("install_book")
    object AppSettings : Screen("app_settings")
    object Connection : Screen("connection")

    object ChapterDetails : Screen("chapter_details") {
        const val bookIdArg = "bookId"
        const val chapterIdArg = "chapterId"
        val routeWithArgs = "$route/{$bookIdArg}/{$chapterIdArg}"
        val arguments: List<NamedNavArgument> = listOf(
            navArgument(bookIdArg) { type = NavType.StringType },
            navArgument(chapterIdArg) { type = NavType.StringType }
        )

        fun createRoute(bookId: String, chapterId: String) = "$route/$bookId/$chapterId"
    }

    object Characters : Screen("characters") {
        const val bookIdArg = "bookId"
        val routeWithArgs = "$route/{$bookIdArg}"
        val arguments: List<NamedNavArgument> = listOf(navArgument(bookIdArg) { type = NavType.StringType })
        fun createRoute(bookId: String) = "$route/$bookId"
    }

    object CharacterDetails : Screen("character_details") {
        const val bookIdArg = "bookId"
        const val characterIdArg = "characterId"
        val routeWithArgs = "$route/{$bookIdArg}/{$characterIdArg}"
        val arguments: List<NamedNavArgument> = listOf(
            navArgument(bookIdArg) { type = NavType.StringType },
            navArgument(characterIdArg) { type = NavType.StringType }
        )

        fun createRoute(bookId: String, characterId: String) = "$route/$bookId/$characterId"
    }

    object BookSettings : Screen("book_settings") {
        const val bookIdArg = "bookId"
        val routeWithArgs = "$route/{$bookIdArg}"
        val arguments: List<NamedNavArgument> = listOf(navArgument(bookIdArg) { type = NavType.StringType })
        fun createRoute(bookId: String) = "$route/$bookId"
    }

    sealed class Bottom(route: String, val label: String, val icon: ImageVector) : Screen(route) {
        object BookHub : Bottom("bottom_book_hub", "Книга", Icons.Default.Book)
        object Player : Bottom("bottom_player", "Плеер", Icons.Default.PlayArrow)
        object LoreHelper : Bottom("bottom_lore_helper", "Помощник", Icons.Default.QuestionAnswer)
        object Library : Bottom("bottom_library", "Библиотека", Icons.AutoMirrored.Filled.LibraryBooks)
    }
}

val bottomNavItems = listOf(
    Screen.Bottom.BookHub,
    Screen.Bottom.Player,
    Screen.Bottom.LoreHelper,
    Screen.Bottom.Library,
)
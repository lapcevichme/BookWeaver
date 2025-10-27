package com.lapcevichme.bookweaver.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.materialkolor.DynamicMaterialExpressiveTheme

/**
 * Обертка, которая применяет DynamicMaterialExpressiveTheme,
 * получая seedColor из BookThemeWrapperViewModel.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BookThemeWrapper(
    viewModel: BookThemeWrapperViewModel = hiltViewModel(getLocalActivity()),
    content: @Composable () -> Unit
) {
    val seedColor by viewModel.themeSeedColor.collectAsStateWithLifecycle()
    val isDark = isSystemInDarkTheme()

    // Используем ту же логику, что и в твоем MainActivity
    DynamicMaterialExpressiveTheme(
        seedColor = seedColor,
        isDark = isDark,
        animate = true,
        motionScheme = MotionScheme.expressive()
        // Ты можешь добавить сюда `content` для `darkColorScheme` и `lightColorScheme`
        // если хочешь использовать стандартный dynamicColor, когда seedColor == defaultSeedColor
    ) {
        content()
    }
}

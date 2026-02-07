package com.lapcevichme.bookweaver.core.ui.theme

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.materialkolor.DynamicMaterialExpressiveTheme

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BookThemeWrapper(
    viewModel: BookThemeWrapperViewModel = hiltViewModel(getLocalActivity()),
    isDark: Boolean,
    content: @Composable () -> Unit
) {
    val seedColor by viewModel.themeSeedColor.collectAsStateWithLifecycle()

    DynamicMaterialExpressiveTheme(
        seedColor = seedColor,
        isDark = isDark,
        animate = true,
        motionScheme = MotionScheme.expressive()
    ) {
        content()
    }
}

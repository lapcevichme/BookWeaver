package com.lapcevichme.bookweaver.features.main

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lapcevichme.bookweaver.core.navigation.AppNavHost
import com.lapcevichme.bookweaver.core.ui.theme.BookWeaverTheme
import com.lapcevichme.bookweaver.domain.model.ThemeSetting
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            val themeSetting by viewModel.themeSetting.collectAsStateWithLifecycle()

            BookWeaverTheme(themeSetting = themeSetting) {

                val view = LocalView.current
                val useDarkIcons = when (themeSetting) {
                    ThemeSetting.LIGHT -> true
                    ThemeSetting.DARK -> false
                    ThemeSetting.SYSTEM -> !isSystemInDarkTheme()
                }

                if (!view.isInEditMode) {
                    SideEffect {
                        val window = (view.context as Activity).window
                        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars =
                            useDarkIcons
                        WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars =
                            useDarkIcons
                        window.statusBarColor = Color.Transparent.hashCode()
                        window.navigationBarColor = Color.Transparent.hashCode()
                    }
                }
                val context = LocalContext.current
                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission(),
                    onResult = { isGranted ->
                        if (!isGranted) {
                            Toast.makeText(
                                context,
                                "Разрешение на уведомления нужно для отображения статуса",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                )

                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavHost(themeSetting = themeSetting)
                }
            }
        }
    }
}

package com.lapcevichme.bookweaver.presentation.ui.main

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
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.lapcevichme.bookweaver.presentation.ui.connection.ConnectionScreen
import com.lapcevichme.bookweaver.presentation.ui.library.LibraryScreen
import com.lapcevichme.bookweaver.ui.theme.BookWeaverTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Разрешаем приложению рисовать под системными панелями
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val qrCodeLauncher = registerForActivityResult(ScanContract()) { result ->
            if (result.contents != null) {
                viewModel.handleQrCodeResult(result.contents)
            }
        }

        setContent {
            // Устанавливаем светлые иконки на системных панелях для темной темы
            val view = LocalView.current
            if (!view.isInEditMode) {
                SideEffect {
                    val window = (view.context as Activity).window
                    WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars =
                        false
                    WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars =
                        false
                }
            }

            BookWeaverTheme {
                val context = LocalContext.current

                // Запрос разрешения на уведомления для Android 13+
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

                // Этот эффект выполнится один раз при старте
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
                    val connectionStatus by viewModel.connectionStatus.collectAsStateWithLifecycle()
                    val isConnected = connectionStatus.startsWith("Подключено")

                    // Анимация перехода между экранами
                    Crossfade(targetState = isConnected, label = "screen_crossfade") { screen ->
                        if (screen) {
                            // Если подключено, показываем экран Библиотеки
                            LibraryScreen()
                        } else {
                            // Если не подключено, показываем экран Подключения
                            ConnectionScreen(
                                onScanQrClick = {
                                    qrCodeLauncher.launch(
                                        ScanOptions()
                                            .setPrompt("Scan a server QR code")
                                            .setBeepEnabled(true)
                                            .setOrientationLocked(false)
                                    )
                                })
                        }
                    }
                }
            }
        }
    }
}



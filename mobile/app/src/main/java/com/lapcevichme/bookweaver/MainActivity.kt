package com.lapcevichme.bookweaver

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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.lapcevichme.bookweaver.ui.theme.BookWeaverTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val qrCodeLauncher = registerForActivityResult(ScanContract()) { result ->
            if (result.contents != null) {
                viewModel.handleQrCodeResult(result.contents)
            }
        }

        setContent {
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
                    // --- ДОБАВЛЕНО: ЗАПУСК АВТОПОДКЛЮЧЕНИЯ ---
                    // Этот код выполнится один раз при запуске Activity.
                    viewModel.reconnectIfPossible()
                }

                MainScreen(
                    viewModel = viewModel,
                    onScanQrClick = {
                        qrCodeLauncher.launch(
                            ScanOptions()
                                .setPrompt("Scan a server QR code")
                                .setBeepEnabled(true)
                        )
                    }
                )
            }
        }
    }
}

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onScanQrClick: () -> Unit
) {
    val connectionStatus by viewModel.connectionStatus.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Connection Status: $connectionStatus",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onScanQrClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Scan QR Code to Connect")
            }

            Spacer(Modifier.height(16.dp))

            Text("Logs", style = MaterialTheme.typography.titleMedium)
            Card(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.padding(8.dp),
                    state = listState,
                    reverseLayout = true
                ) {
                    items(logs.reversed()) { log ->
                        Text(log, modifier = Modifier.padding(vertical = 4.dp))
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

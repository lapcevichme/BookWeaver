package com.lapcevichme.bookweaver.presentation.ui.connection

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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lapcevichme.bookweaver.presentation.ui.main.MainViewModel

@Composable
fun ConnectionScreen(
    onScanQrClick: () -> Unit,
    viewModel: MainViewModel = hiltViewModel()
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

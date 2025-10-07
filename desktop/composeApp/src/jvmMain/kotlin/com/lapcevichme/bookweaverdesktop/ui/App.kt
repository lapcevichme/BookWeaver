package com.lapcevichme.bookweaverdesktop.ui

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.lapcevichme.bookweaverdesktop.model.ServerState
import com.lapcevichme.bookweaverdesktop.server.ServerManager
import java.awt.image.BufferedImage

private const val QR_SIZE = 256

@Composable
@Preview
fun App(serverManager: ServerManager = ServerManager) {
    val state by serverManager.serverState.collectAsState()
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (val currentState = state) {
            is ServerState.Disconnected -> {
                Text("Server stopped.")
                Spacer(Modifier.height(8.dp))
                CircularProgressIndicator()
            }

            is ServerState.ReadyForConnection -> {
                Text("Server started. Click to show QR code for connection.")
                Spacer(Modifier.height(16.dp))
                Button(onClick = { serverManager.showConnectionInstructions() }) {
                    Text("Connect device")
                }
            }

            is ServerState.AwaitingConnection -> {
                Text("Scan the QR code on your phone for initial connection.")
                Spacer(Modifier.height(16.dp))
                QrCodeImage(currentState.qrCodeData)
                Spacer(Modifier.height(16.dp))
                Text("Awaiting connection...", style = MaterialTheme.typography.caption)
            }

            is ServerState.PeerConnected -> {
                Text("Device connected!", style = MaterialTheme.typography.h6)
                Spacer(Modifier.height(8.dp))
                Text("Address: ${currentState.peerInfo}")
            }

            is ServerState.Error -> {
                Text("An error occurred:", style = MaterialTheme.typography.h6, color = MaterialTheme.colors.error)
                Spacer(Modifier.height(8.dp))
                Text(currentState.message, color = MaterialTheme.colors.error)
            }
        }
    }
}

@Composable
fun QrCodeImage(text: String) {
    val imageBitmap = remember(text) { generateQrCodeBitmap(text) }
    Image(
        bitmap = imageBitmap,
        contentDescription = "QR code for connection",
        modifier = Modifier.size(QR_SIZE.dp)
    )
}

private fun generateQrCodeBitmap(text: String): ImageBitmap {
    val qrCodeWriter = QRCodeWriter()
    val bitMatrix = qrCodeWriter.encode(text, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE)
    val bufferedImage = BufferedImage(QR_SIZE, QR_SIZE, BufferedImage.TYPE_INT_RGB)
    for (x in 0 until QR_SIZE) {
        for (y in 0 until QR_SIZE) {
            bufferedImage.setRGB(x, y, if (bitMatrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        }
    }
    return bufferedImage.toComposeImageBitmap()
}
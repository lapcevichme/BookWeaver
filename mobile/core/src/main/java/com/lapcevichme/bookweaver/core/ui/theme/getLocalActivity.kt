package com.lapcevichme.bookweaver.core.ui.theme

import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext


@Composable
fun getLocalActivity(): ComponentActivity {
    var context = LocalContext.current
    while (context is ContextWrapper) {
        if (context is ComponentActivity) {
            return context
        }
        context = context.baseContext
    }
    throw IllegalStateException("No ComponentActivity found in context. Это очень странно.")
}
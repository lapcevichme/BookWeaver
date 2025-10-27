package com.lapcevichme.bookweaver.core.ui.theme

import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Безопасно находит ComponentActivity из LocalContext.
 * Это более надежный способ, чем (LocalContext.current as ComponentActivity).
 */
@Composable
fun getLocalActivity(): ComponentActivity {
    var context = LocalContext.current
    while (context is ContextWrapper) {
        if (context is ComponentActivity) {
            return context
        }
        context = context.baseContext
    }
    // Этого никогда не должно случиться в работающем приложении
    throw IllegalStateException("No ComponentActivity found in context. Это очень странно.")
}
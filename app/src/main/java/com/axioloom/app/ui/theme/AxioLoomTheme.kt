package com.axioloom.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AxioLoomColors = darkColorScheme(
    primary = Color(0xFFFFB300),
    onPrimary = Color(0xFF241A00),
    secondary = Color(0xFF9BA3B0),
    onSecondary = Color(0xFF15181D),
    background = Color(0xFF121417),
    onBackground = Color(0xFFF4F5F7),
    surface = Color(0xFF191D22),
    onSurface = Color(0xFFF4F5F7),
    surfaceVariant = Color(0xFF252A31),
    onSurfaceVariant = Color(0xFFBEC4CE),
    error = Color(0xFFDC3B45),
    onError = Color.White,
    outline = Color(0xFF414852)
)

@Composable
fun AxioLoomTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AxioLoomColors,
        content = content
    )
}

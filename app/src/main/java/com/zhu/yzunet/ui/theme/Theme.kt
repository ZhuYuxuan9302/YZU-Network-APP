package com.zhu.yzunet.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1B6EF3),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE7FF),
    onPrimaryContainer = Color(0xFF001A41),
    secondary = Color(0xFF2E7D5B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6F2E3),
    onSecondaryContainer = Color(0xFF002114),
    background = Color(0xFFF6F8FC),
    onBackground = Color(0xFF141A22),
    surface = Color.White,
    onSurface = Color(0xFF141A22),
    surfaceVariant = Color(0xFFEDF1F8),
    onSurfaceVariant = Color(0xFF5A6472),
    outline = Color(0xFFC5CDDA),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF9FC0FF),
    onPrimary = Color(0xFF002F63),
    primaryContainer = Color(0xFF00458F),
    onPrimaryContainer = Color(0xFFDCE7FF),
    secondary = Color(0xFF9BD6B8),
    onSecondary = Color(0xFF003823),
    secondaryContainer = Color(0xFF1E4A38),
    onSecondaryContainer = Color(0xFFD6F2E3),
    background = Color(0xFF0F141B),
    onBackground = Color(0xFFE3E6EC),
    surface = Color(0xFF161C25),
    onSurface = Color(0xFFE3E6EC),
    surfaceVariant = Color(0xFF222A35),
    onSurfaceVariant = Color(0xFFA7B0BE),
    outline = Color(0xFF3F4854),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

@Composable
fun YzuTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        content = content,
    )
}

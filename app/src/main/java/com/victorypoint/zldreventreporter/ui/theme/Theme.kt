package com.victorypoint.zldreventreporter.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = ZldrOrange,
    onPrimary = Color.White,
    secondary = ZldrBlue,
    onSecondary = Color.White,
    background = ZldrBackground,
    surface = ZldrSurface,
    onBackground = Color.White,
    onSurface = Color.White,
)

@Composable
fun ZldrReporterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content,
    )
}

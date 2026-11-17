package com.yuanhang.ssh.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Brand palette: deep navy + terminal green accent
val Navy900 = Color(0xFF0B1524)
val Navy800 = Color(0xFF121E30)
val Navy700 = Color(0xFF1B2A42)
val Slate500 = Color(0xFF64748B)
val Slate300 = Color(0xFFCBD5E1)
val AccentGreen = Color(0xFF22C55E)
val AccentGreenDim = Color(0xFF16A34A)
val AccentCyan = Color(0xFF06B6D4)
val AccentAmber = Color(0xFFF59E0B)
val DangerRed = Color(0xFFEF4444)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1D4ED8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBE4FF),
    onPrimaryContainer = Color(0xFF0B2A6B),
    secondary = AccentGreenDim,
    onSecondary = Color.White,
    tertiary = AccentCyan,
    surface = Color(0xFFF7F9FC),
    onSurface = Color(0xFF101828),
    surfaceVariant = Color(0xFFE6EBF2),
    onSurfaceVariant = Color(0xFF475467),
    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF101828),
    error = DangerRed,
    outline = Color(0xFFD0D7E4)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF86A9FF),
    onPrimary = Color(0xFF0B2A6B),
    primaryContainer = Color(0xFF1E3A6B),
    onPrimaryContainer = Color(0xFFDBE4FF),
    secondary = AccentGreen,
    onSecondary = Navy900,
    tertiary = AccentCyan,
    surface = Navy800,
    onSurface = Color(0xFFE8EEF6),
    surfaceVariant = Navy700,
    onSurfaceVariant = Slate300,
    background = Navy900,
    onBackground = Color(0xFFE8EEF6),
    error = DangerRed,
    outline = Color(0xFF334155)
)

private val AppTypography = Typography()

@Composable
fun YuanhangSSHTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content
    )
}

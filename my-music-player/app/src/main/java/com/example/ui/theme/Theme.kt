package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val MidnightNeonColorScheme = darkColorScheme(
    primary = CyberTurquoise,
    secondary = NeonPink,
    tertiary = NeonPurple,
    background = MidnightBlack,
    surface = CharcoalCard,
    surfaceVariant = SurfaceGrey,
    onPrimary = Color.Black,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true, // Force dark theme for premium music equipment look
    dynamicColor: Boolean = false, // Disable dynamic colors to preserve our unique brand identity
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = MidnightNeonColorScheme,
        typography = Typography,
        content = content
    )
}


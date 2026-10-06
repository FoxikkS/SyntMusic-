package com.syntmusic.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Palette {
    val Background = Color(0xFF0D0D0D)
    val Surface = Color(0xFF171717)
    val SurfaceHigh = Color(0xFF202020)
    val Primary = Color(0xFFFFFFFF)
    val Secondary = Color(0xFF8A8A8A)
    val Inactive = Color(0xFF555555)
}

private val colors = darkColorScheme(
    primary = Palette.Primary,
    onPrimary = Palette.Background,
    secondary = Palette.Secondary,
    onSecondary = Palette.Background,
    background = Palette.Background,
    onBackground = Palette.Primary,
    surface = Palette.Background,
    onSurface = Palette.Primary,
    surfaceVariant = Palette.Surface,
    onSurfaceVariant = Palette.Secondary,
    surfaceContainerLowest = Palette.Background,
    surfaceContainerLow = Palette.Surface,
    surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.SurfaceHigh,
    surfaceContainerHighest = Palette.SurfaceHigh,
    outline = Palette.Inactive,
    outlineVariant = Palette.Surface,
)

private val typography = Typography().run {
    copy(
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold),
        titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun SyntTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography) {
        CompositionLocalProvider(LocalContentColor provides Palette.Primary, content = content)
    }
}

package com.sunli.tvlibrary.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

data class TvPalette(
    val isDark: Boolean,
    val accent: Color,
    val background: Color,
    val surfaceDark: Color,
    val surfaceLight: Color,
    val textPrimary: Color,
    val textSecondary: Color
)

private val DarkPalette = TvPalette(
    isDark = true,
    accent = Color(0xFFE53935),
    background = Color(0xFF0B0D11),
    surfaceDark = Color(0xFF151A21),
    surfaceLight = Color(0xFF232A33),
    textPrimary = Color(0xFFF2F3F5),
    textSecondary = Color(0xFF9AA3AF)
)

private val LightPalette = TvPalette(
    isDark = false,
    accent = Color(0xFFD32F2F),
    background = Color(0xFFF3F4F6),
    surfaceDark = Color(0xFFFFFFFF),
    surfaceLight = Color(0xFFE4E8ED),
    textPrimary = Color(0xFF171A1F),
    textSecondary = Color(0xFF636D7B)
)

private object PaletteHolder {
    var current by mutableStateOf(DarkPalette)
}

val IsDarkTheme: Boolean get() = PaletteHolder.current.isDark
val FocusRing: Color get() = if (IsDarkTheme) Color.White else Color(0xFF1B1F26)
val FocusFill: Color get() = if (IsDarkTheme) Color.White else Color(0xFF1B1F26)
val FocusText: Color get() = if (IsDarkTheme) Color.Black else Color.White
val PrimaryButton: Color get() = if (IsDarkTheme) Color(0xFFDDE3EA) else Color(0xFF242A32)
val PrimaryButtonText: Color get() = if (IsDarkTheme) Color(0xFF12151A) else Color.White
val Accent: Color get() = PaletteHolder.current.accent
val Background: Color get() = PaletteHolder.current.background
val SurfaceDark: Color get() = PaletteHolder.current.surfaceDark
val SurfaceLight: Color get() = PaletteHolder.current.surfaceLight
val TextPrimary: Color get() = PaletteHolder.current.textPrimary
val TextSecondary: Color get() = PaletteHolder.current.textSecondary

@Composable
fun TvLibraryTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    val palette = if (dark) DarkPalette else LightPalette
    PaletteHolder.current = palette
    val scheme = if (dark) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = Color.White,
            secondary = Color(0xFF64B5F6),
            background = palette.background,
            onBackground = palette.textPrimary,
            surface = palette.surfaceDark,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.surfaceLight,
            onSurfaceVariant = palette.textSecondary
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = Color.White,
            secondary = Color(0xFF1976D2),
            background = palette.background,
            onBackground = palette.textPrimary,
            surface = palette.surfaceDark,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.surfaceLight,
            onSurfaceVariant = palette.textSecondary
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

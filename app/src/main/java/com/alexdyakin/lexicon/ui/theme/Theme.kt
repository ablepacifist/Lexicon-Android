package com.alexdyakin.lexicon.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Warm parchment-and-ink palette, taken from the Lexicon room/banner art
private val Gold = Color(0xFFC8A24C)
private val GoldBright = Color(0xFFE3C46F)
private val Ink = Color(0xFF17130E)
private val InkSoft = Color(0xFF241D16)
private val Parchment = Color(0xFFF3E7CE)
private val ParchmentDim = Color(0xFFD9C9A6)
private val Moss = Color(0xFF6B8F5E)
private val Ember = Color(0xFFB4552F)

private val DarkColors = darkColorScheme(
    primary = Gold,
    onPrimary = Ink,
    primaryContainer = InkSoft,
    onPrimaryContainer = GoldBright,
    secondary = Moss,
    onSecondary = Ink,
    background = Ink,
    onBackground = Parchment,
    surface = InkSoft,
    onSurface = Parchment,
    surfaceVariant = Color(0xFF2E251B),
    onSurfaceVariant = ParchmentDim,
    error = Ember,
    outline = Color(0xFF5A4B36),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF7A5A18),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF0E0BC),
    onPrimaryContainer = Color(0xFF2A1E06),
    secondary = Moss,
    onSecondary = Color.White,
    background = Parchment,
    onBackground = Ink,
    surface = Color(0xFFFBF3E2),
    onSurface = Ink,
    surfaceVariant = Color(0xFFE7DABE),
    onSurfaceVariant = Color(0xFF4B4033),
    error = Ember,
    outline = Color(0xFFA08C68),
)

@Composable
fun LexiconTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colors,
        typography = Typography(),
        content = content,
    )
}

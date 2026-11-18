package com.alexdyakin.lexicon.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Every Material 3 role has a value here.
 *
 * The previous scheme set 14 of them and left the rest on Material's purple defaults — which was
 * not theoretical: `QueueManagerScreen` and `LiveStreamScreen` both paint error cards with
 * `errorContainer`, and were getting stock purple in a gold app. `tertiary`, the five
 * `surfaceContainer*` steps, `outlineVariant` and `scrim` were in the same state.
 */
private val LexDark = darkColorScheme(
    primary = LexPalette.Indigo,
    onPrimary = Color.White,
    primaryContainer = LexPalette.IndigoDeep,
    onPrimaryContainer = LexPalette.Bright,
    inversePrimary = LexPalette.IndigoLight,

    secondary = LexPalette.Violet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF3B2E63),
    onSecondaryContainer = LexPalette.Bright,

    tertiary = LexPalette.Sky,
    onTertiary = Color(0xFF05131C),
    tertiaryContainer = Color(0xFF123447),
    onTertiaryContainer = LexPalette.Bright,

    background = LexPalette.Ground,
    onBackground = LexPalette.Text,

    surface = LexPalette.Panel,
    onSurface = LexPalette.Text,
    surfaceVariant = LexPalette.Raised,
    onSurfaceVariant = LexPalette.Muted,
    surfaceTint = LexPalette.Indigo,
    surfaceDim = LexPalette.Ground,
    surfaceBright = LexPalette.High,
    surfaceContainerLowest = LexPalette.Lowest,
    surfaceContainerLow = LexPalette.Panel,
    surfaceContainer = LexPalette.Raised,
    surfaceContainerHigh = LexPalette.High,
    surfaceContainerHighest = LexPalette.Highest,

    inverseSurface = LexPalette.Bright,
    inverseOnSurface = LexPalette.Ground,

    error = LexPalette.Error,
    onError = Color.White,
    errorContainer = Color(0xFF4A1515),
    onErrorContainer = Color(0xFFFFD9D9),

    outline = LexPalette.Line,
    outlineVariant = LexPalette.LineSoft,
    scrim = Color(0xCC000000),
)

/**
 * The app is dark, full stop — there is no `darkTheme` parameter any more.
 *
 * Light mode was removed rather than fixed. It had never worked: nine call sites across
 * `PlayerScreen`, `VideoPlayerScreen` and `AlchemyScreen` drew `onSurfaceVariant` text directly
 * onto a near-black scrim, which is invisible on a light scheme, and the old `OnScrim`/`OnScrimDim`
 * constants existed purely to work around that for the six screens that remembered to use them.
 * One scheme deletes the workaround and the bug together. The website has no light mode either.
 */
@Composable
fun LexiconTheme(content: @Composable () -> Unit) {
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Light icons on a dark bar, always — there is only one scheme now.
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = LexDark,
        typography = LexTypography,
        shapes = LexShapes,
        content = content,
    )
}

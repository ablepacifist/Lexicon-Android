package com.alexdyakin.lexicon.ui.voice

import androidx.compose.ui.graphics.Color

/**
 * The bridge web client's Discord dark palette, mirrored from the `:root` block in
 * `mumble-bridge/public/css/style.css`.
 *
 * This screen deliberately does **not** use the app's medieval `ScreenScaffold`
 * styling. The bridge is its own product with its own established look, and the point
 * of the mobile client is to be the same thing on a phone — parchment and background
 * art would make it a different app that happens to show the same messages.
 *
 * Names match the CSS custom properties so the two can be diffed by eye.
 */
object BridgeColors {
    val bgPrimary = Color(0xFF313338)     // chat area
    val bgSecondary = Color(0xFF2B2D31)   // channel sidebar
    val bgTertiary = Color(0xFF1E1F22)    // deepest wells
    val bgAccent = Color(0xFF404249)
    val bgFloating = Color(0xFF111214)

    /** Hover and active fills; both are translucent greys over whatever is beneath. */
    val bgModSubtle = Color(0x4D4F545C)
    val bgModStrong = Color(0x994F545C)

    val textNormal = Color(0xFFDBDEE1)
    val textMuted = Color(0xFF949BA4)
    val textFaint = Color(0xFF6D6F78)
    val textLink = Color(0xFF00A8FC)
    val textPositive = Color(0xFF23A55A)
    val textWarning = Color(0xFFF0B232)
    val textDanger = Color(0xFFF23F43)

    val brandPrimary = Color(0xFF5865F2)
    val brandHover = Color(0xFF4752C4)

    val channelIcon = Color(0xFF80848E)
    val interactiveNormal = Color(0xFFB5BAC1)
    val interactiveHover = Color(0xFFDBDEE1)
    val interactiveActive = Color(0xFFFFFFFF)
    val interactiveMuted = Color(0xFF4E5058)

    val headerPrimary = Color(0xFFF2F3F5)
    val headerSecondary = Color(0xFFB5BAC1)

    val inputBg = Color(0xFF383A40)

    /**
     * Avatar fallback colours. The web client shows a real image; when one is missing
     * an initial on a stable per-name colour reads better than a grey blank.
     */
    private val avatarPalette = listOf(
        Color(0xFF5865F2), Color(0xFF23A55A), Color(0xFFF0B232),
        Color(0xFFF23F43), Color(0xFFEB459E), Color(0xFF3BA55C),
    )

    fun avatarColor(name: String): Color =
        avatarPalette[(name.hashCode().let { if (it < 0) -it else it }) % avatarPalette.size]
}

package com.alexdyakin.lexicon.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * The app's raw palette, taken from the website so the phone and `alex-dyakin.com` read as one
 * product. These are values, not roles — [LexDark] in `Theme.kt` is what maps them onto Material's
 * colour roles, and screens should reach for `MaterialTheme.colorScheme` rather than these.
 *
 * Deliberately **not** `private`, which is what went wrong before: the old palette was private to
 * `Theme.kt`, so screens that needed a colour re-typed the hex instead, and the app ended up with
 * 129 hardcoded `Color(0xFF…)` literals in 89 distinct values — including a whole second palette
 * inside the media player. Nothing outside this file should ever spell a hex code again.
 */
object LexPalette {

    // ---- Straight from the site (Lexicon/src/App.css, PageStyles.css). Do not invent values here.

    /** Page ground. `body { background: #0a0a0f }`. */
    val Ground = Color(0xFF0A0A0F)

    /** Card and panel fill. */
    val Panel = Color(0xFF0E0E14)

    /** Body text. `body { color: #e0e0e8 }`. */
    val Text = Color(0xFFE0E0E8)

    /** Secondary text, a step down from [Text]. */
    val Dim = Color(0xFFC0C0D0)

    /** Captions, metadata, disabled labels. */
    val Muted = Color(0xFFA0A0B0)

    /** Headings and anything that needs to sit above [Text]. */
    val Bright = Color(0xFFF0F0F5)

    /** The site's primary accent, and by far its most-used colour. */
    val Indigo = Color(0xFF6366F1)
    val IndigoDeep = Color(0xFF4F46E5)
    val IndigoLight = Color(0xFF8B8BF5)

    val Violet = Color(0xFF8B5CF6)
    val Sky = Color(0xFF38BDF8)

    val Success = Color(0xFF4CAF50)
    val Warning = Color(0xFFFFC107)
    val Error = Color(0xFFEF4444)

    // ---- Derived. The web has two background steps; Material 3 wants five surface-container
    // levels plus outlines, so these interpolate Ground -> Panel and upward. Marked as derived so
    // nobody later believes they came out of the stylesheet.

    val Raised = Color(0xFF14141C)
    val High = Color(0xFF1A1A24)
    val Highest = Color(0xFF20202C)
    val Lowest = Color(0xFF07070B)

    /** Borders and dividers. */
    val Line = Color(0xFF26263A)

    /** A quieter divider, for separators inside a card that already has a border. */
    val LineSoft = Color(0xFF1C1C28)
}

/**
 * The app's ground, in one place.
 *
 * This is what replaced the full-screen background photographs: a vertical gradient that lifts
 * very slightly in the middle so a screen does not read as a flat rectangle. A `verticalGradient`
 * with no explicit stops sizes itself to whatever it is drawn into, so one instance is correct
 * everywhere and nothing is allocated per frame.
 *
 * Used by [com.alexdyakin.lexicon.ui.components.ScreenScaffold] and by the two auth screens,
 * which sit outside it.
 */
val LexGround: Brush = Brush.verticalGradient(
    listOf(LexPalette.Ground, LexPalette.Panel, LexPalette.Ground)
)

/**
 * The one traffic-light triple.
 *
 * Four screens each invented their own before this existed, and two of them disagreed on the
 * values — `HoldfastCrops`, `StorageDashboardScreen` and `PokemonScreen` used one set while
 * `LexiconScreen` used a different green/amber/red entirely, so the same "healthy" state was two
 * different colours depending on which screen you were looking at.
 */
object Status {
    val ok = LexPalette.Success
    val warn = LexPalette.Warning
    val danger = LexPalette.Error

    /**
     * Grades a 0..1 fraction where **higher is healthier** — disk free, crop health, remaining HP.
     * Invert the argument for metrics where higher is worse, such as disk *used*.
     */
    fun forFraction(fraction: Float): Color = when {
        fraction >= 0.66f -> ok
        fraction >= 0.33f -> warn
        else -> danger
    }
}

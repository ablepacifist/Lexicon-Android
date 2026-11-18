package com.alexdyakin.lexicon.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Spacing, on a 4dp grid.
 *
 * Before this existed the app used **19 different padding values** across the UI — 16, 12, 8, 4,
 * 6, 10, 14, 2, 20, 24, 18, 1, 28, 5, 40, 32, 11, 7 and 48 — which is why nothing lined up with
 * anything else. Every `.dp` literal in `ui/` maps onto one of these by nearest neighbour.
 *
 * These are plain objects rather than a `CompositionLocal`. A local would only earn its keep if
 * the values varied by theme or window size; with one theme it costs a local read per use and
 * breaks inside `@Preview` unless every preview is wrapped. If responsive sizing is ever wanted,
 * swapping one object at the `LexiconTheme` boundary covers it.
 */
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp

    /**
     * The one horizontal screen gutter. [com.alexdyakin.lexicon.ui.components.ScreenScaffold]
     * applies it; screens must not re-apply it. This single default is what makes every screen
     * in the app line up, and it is why the scaffold now hands content its padding.
     */
    val gutter = lg
}

/**
 * Corner radii, from the website's 8 / 16 / 20px.
 *
 * The app had 17 distinct radii, including three different spellings of "pill"
 * (`RoundedCornerShape(50)` as a percentage, `RoundedCornerShape(50.dp)` as an absolute, and
 * `RoundedCornerShape(999.dp)`). [pill] is the only one now.
 */
object Radii {
    val sm = 8.dp
    val md = 16.dp
    val lg = 20.dp
    val xl = 28.dp

    /** A true pill at any height, which the absolute-dp spellings were not. */
    val pill = RoundedCornerShape(percent = 50)
}

/** Control geometry. Nothing interactive is ever smaller than [touch]. */
object Sizes {
    /** The minimum interactive size. No exceptions — this is an accessibility floor, not a style. */
    val touch = 48.dp

    /** Standard height for buttons, text fields and chips. */
    val control = 48.dp
    val controlSm = 36.dp

    val iconSm = 18.dp
    val icon = 24.dp
    val iconLg = 32.dp

    /** The play/pause control at the centre of a [com.alexdyakin.lexicon.ui.components.TransportBar]. */
    val transportPrimary = 64.dp

    /** Every other transport control. Two of these a side is the TransportBar's contract. */
    val transportSlot = 48.dp

    val avatarSm = 32.dp
    val avatar = 40.dp
    val avatarLg = 80.dp
}

/** Surface elevation. Kept shallow — the palette is almost flat, so shadows do little work. */
object Elevation {
    val flat = 0.dp
    val card = 1.dp
    val raised = 3.dp
    val dialog = 6.dp
}

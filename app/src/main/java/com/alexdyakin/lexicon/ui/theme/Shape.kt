package com.alexdyakin.lexicon.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes

/**
 * The shape scale, wired into `MaterialTheme` for the first time.
 *
 * `LexiconTheme` never passed `shapes =` at all, so every Material component fell back to the
 * platform default and screens hand-wrote `RoundedCornerShape(n.dp)` instead — 17 distinct radii
 * across the app. With this in place, `Card`, `Button`, `TextField`, `AlertDialog` and the rest
 * are correct without being told, and [Radii] covers the handful of cases that need a shape
 * directly.
 */
val LexShapes = Shapes(
    extraSmall = RoundedCornerShape(Radii.sm),
    small = RoundedCornerShape(Radii.sm),
    medium = RoundedCornerShape(Radii.md),
    large = RoundedCornerShape(Radii.lg),
    extraLarge = RoundedCornerShape(Radii.xl),
)

package com.alexdyakin.lexicon.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.alexdyakin.lexicon.R

/**
 * Inter, the website's typeface (`@import … family=Inter` in `Lexicon/src/App.css`).
 *
 * Bundled as four static TTFs rather than fetched, for two reasons. Downloadable fonts need Play
 * Services, which fails on a GMS-less device and flashes a fallback on first paint — bad for an
 * APK we distribute ourselves. And the variable build is out: `FontVariation` is API 26+ while
 * this app's `minSdk` is 24, so a variable font would render every weight identically on the
 * oldest supported devices.
 *
 * Licence: SIL Open Font License 1.1, copy at `app/licenses/inter-OFL.txt`.
 */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/**
 * `letterSpacing` in `em` rather than `sp` so tracking scales with the type size, which is what
 * the site's `-0.02em` / `-0.03em` means. Expressed in `sp` it would have to be recalculated per
 * role and would not survive the user's font-scale setting.
 */
private fun TextStyle.inter(weight: FontWeight, tracking: TextUnit = 0.sp) =
    copy(fontFamily = Inter, fontWeight = weight, letterSpacing = tracking)

/**
 * The full Material 3 type scale on Inter. All fifteen roles are set — the app previously passed
 * a bare `Typography()`, so every screen got the platform default and 49 inline `fontSize = N.sp`
 * values grew up around it to compensate.
 *
 * Headings carry the site's negative tracking; body and label text do not, because tightening
 * small text hurts legibility rather than helping it.
 */
val LexTypography: Typography = Typography().run {
    copy(
        displayLarge = displayLarge.inter(FontWeight.Bold, (-0.03).em),
        displayMedium = displayMedium.inter(FontWeight.Bold, (-0.03).em),
        displaySmall = displaySmall.inter(FontWeight.Bold, (-0.02).em),

        headlineLarge = headlineLarge.inter(FontWeight.Bold, (-0.03).em),
        headlineMedium = headlineMedium.inter(FontWeight.SemiBold, (-0.02).em),
        headlineSmall = headlineSmall.inter(FontWeight.SemiBold, (-0.02).em),

        titleLarge = titleLarge.inter(FontWeight.SemiBold, (-0.02).em),
        titleMedium = titleMedium.inter(FontWeight.SemiBold),
        titleSmall = titleSmall.inter(FontWeight.Medium),

        bodyLarge = bodyLarge.inter(FontWeight.Normal),
        bodyMedium = bodyMedium.inter(FontWeight.Normal),
        bodySmall = bodySmall.inter(FontWeight.Normal),

        labelLarge = labelLarge.inter(FontWeight.Medium),
        labelMedium = labelMedium.inter(FontWeight.Medium),
        labelSmall = labelSmall.inter(FontWeight.Medium),
    )
}

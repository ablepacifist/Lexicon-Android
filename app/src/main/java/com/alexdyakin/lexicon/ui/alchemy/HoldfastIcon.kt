package com.alexdyakin.lexicon.ui.alchemy

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.alexdyakin.lexicon.data.ApiUrls
import com.alexdyakin.lexicon.data.HoldfastAsset

/**
 * The asset manifest for the Holdfast screen. Provided once in HoldfastScreen so every
 * card and chip can draw its icon without the map being threaded through each tab.
 */
internal val LocalHoldfastAssets = staticCompositionLocalOf<Map<String, HoldfastAsset>> { emptyMap() }

// Manifest keys, mirroring the server's holdfast-assets/manifest.json.
internal fun buildingAssetKey(type: String) = "building.$type"
internal fun resourceAssetKey(resource: String) = "resource.${resource.lowercase()}"
internal fun castleAssetKey(castleType: String) = "castle.$castleType"
internal fun tabAssetKey(tab: HoldfastTab) = "tab.${tab.name.lowercase()}"

/**
 * The image alchemyServer lists for [key], or its emoji when there is no image or it
 * fails to load. Draws nothing for a key the manifest does not know (or before it loads).
 * Decorative: the text beside it already names the thing, so no content description.
 */
@Composable
internal fun HoldfastIcon(key: String, size: Dp, modifier: Modifier = Modifier) {
    val asset = LocalHoldfastAssets.current[key] ?: return
    val url = asset.url?.takeIf { it.isNotBlank() }?.let(ApiUrls::holdfastAssetUrl)
    if (url == null) {
        EmojiGlyph(asset.emoji, size, modifier)
        return
    }
    SubcomposeAsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier.size(size),
        error = { EmojiGlyph(asset.emoji, size) },
    )
}

@Composable
private fun EmojiGlyph(emoji: String, size: Dp, modifier: Modifier = Modifier) {
    if (emoji.isBlank()) return
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        // Emoji glyphs draw a little larger than their font size; 0.7 keeps them inside the box.
        Text(emoji, fontSize = (size.value * 0.7f).sp, lineHeight = (size.value * 0.8f).sp)
    }
}

/** Standard icon sizes, so the same thing reads the same everywhere on the screen. */
internal object HoldfastIconSize {
    val Chip = 18.dp
    val Row = 28.dp
    val Card = 48.dp
    val Header = 56.dp
    val Tab = 22.dp
}

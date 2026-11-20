package com.alexdyakin.lexicon.data

import com.alexdyakin.lexicon.BuildConfig
import java.net.URI

/**
 * Backend endpoints. The app always talks to the public HTTPS hosts — unlike the
 * website, it has no LAN/tunnel variants to sniff for.
 *
 * The values themselves are not hard-coded here: they come from BuildConfig, which
 * app/build.gradle.kts fills in at configuration time from the one destination
 * registry (destinations.defaults.json, overlaid by the root `.env`, overlaid by real
 * env vars). See LexiconAndroid/.env.example.
 */
object ApiUrls {
    val LEXICON: String = BuildConfig.PUBLIC_LEXICON_URL.trimEnd('/') + "/"
    val ALCHEMY: String = BuildConfig.PUBLIC_ALCHEMY_URL.trimEnd('/') + "/"
    val POKEMON: String = BuildConfig.PUBLIC_POKEMON_URL.trimEnd('/') + "/"
    val VOICE: String = BuildConfig.PUBLIC_BRIDGE_URL.trimEnd('/') + "/"

    /**
     * Hosts that accept the mobile bearer token.
     *
     * Lexicon issues and rotates it; Pokemon validates it read-only against the
     * shared database. Alchemy is absent on purpose — its endpoints take an
     * explicit playerId and never read a session, so the token means nothing there.
     */
    val TOKEN_AWARE_HOSTS: Set<String> = setOfNotNull(
        hostOf(BuildConfig.PUBLIC_LEXICON_URL),
        hostOf(BuildConfig.PUBLIC_POKEMON_URL),
    )

    /**
     * Plain `java.net.URI`, not `android.net.Uri`: the latter is a stub off-device and
     * would crash the JVM unit tests that exercise this object indirectly.
     */
    private fun hostOf(url: String): String? = runCatching { URI(url).host }.getOrNull()

    /** Sprite for a caught Pokemon, served by the Pokemon backend. */
    fun spriteUrl(spriteKey: String): String = POKEMON + "api/pokemon/sprites/" + spriteKey

    /**
     * Sprite for an inventory item (balls, berries, stones), served by the Pokemon
     * backend from a different folder than species sprites — hence the second helper
     * rather than a flag on the first.
     */
    fun itemSpriteUrl(sprite: String): String = POKEMON + "api/pokemon/item-sprites/" + sprite

    /**
     * Full URL for a Holdfast asset. The manifest gives server paths such as
     * "/api/holdfast/assets/buildings/granary.png?v=…"; an absolute URL is used as is.
     */
    fun holdfastAssetUrl(path: String): String =
        if (path.startsWith("http://") || path.startsWith("https://")) path else ALCHEMY + path.trimStart('/')

    /** Stream URL for a media file, served by the Lexicon backend. */
    fun streamUrl(mediaId: Int): String = LEXICON + "api/media/stream/$mediaId"
}

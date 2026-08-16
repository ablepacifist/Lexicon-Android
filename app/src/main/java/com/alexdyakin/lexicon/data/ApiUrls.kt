package com.alexdyakin.lexicon.data

/**
 * Backend endpoints. The app always talks to the public HTTPS hosts — unlike the
 * website, it has no LAN/tunnel variants to sniff for.
 */
object ApiUrls {
    const val LEXICON = "https://api.alex-dyakin.com/"
    const val ALCHEMY = "https://alchemy.alex-dyakin.com/"
    const val POKEMON = "https://poke.alex-dyakin.com/"
    const val VOICE = "https://voice.alex-dyakin.com/"

    /**
     * Hosts that accept the mobile bearer token.
     *
     * Lexicon issues and rotates it; Pokemon validates it read-only against the
     * shared database. Alchemy is absent on purpose — its endpoints take an
     * explicit playerId and never read a session, so the token means nothing there.
     */
    val TOKEN_AWARE_HOSTS = setOf(
        "api.alex-dyakin.com",
        "poke.alex-dyakin.com",
    )

    /** Sprite for a caught Pokemon, served by the Pokemon backend. */
    fun spriteUrl(spriteKey: String): String = POKEMON + "api/pokemon/sprites/" + spriteKey

    /** Stream URL for a media file, served by the Lexicon backend. */
    fun streamUrl(mediaId: Int): String = LEXICON + "api/media/stream/$mediaId"
}

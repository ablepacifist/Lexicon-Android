package com.alexdyakin.lexicon.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Colours that carry meaning inside the games, named for what they mean.
 *
 * They were hex literals scattered through the screens (21 in PokemonScreen alone), so the
 * same idea had several shades and nothing could be adjusted in one place. The site-wide
 * palette stays in [LexPalette]; these are the game-specific additions.
 */
object PokemonTypeColors {
    private val byType = mapOf(
        "normal" to Color(0xFF9FA19F),
        "fire" to Color(0xFFEE8130),
        "water" to Color(0xFF6390F0),
        "grass" to Color(0xFF7AC74C),
        "electric" to Color(0xFFF7D02C),
        "psychic" to Color(0xFFF95587),
        "ice" to Color(0xFF96D9D6),
        "dragon" to Color(0xFF6F35FC),
        "dark" to Color(0xFF705746),
        "fairy" to Color(0xFFD685AD),
        "fighting" to Color(0xFFC22E28),
        "flying" to Color(0xFFA98FF3),
        "poison" to Color(0xFFA33EA1),
        "ground" to Color(0xFFE2BF65),
        "rock" to Color(0xFFB6A136),
        "bug" to Color(0xFFA6B91A),
        "ghost" to Color(0xFF735797),
        "steel" to Color(0xFFB7B7CE),
    )
    private val unknown = Color(0xFF9FA19F)

    fun of(type: String): Color = byType[type.lowercase()] ?: unknown
}

/** Gym teams, as on the website's map. */
object TeamColors {
    val Valor = Color(0xFFEF4444)
    val Mystic = Color(0xFF3B82F6)
    val Instinct = Color(0xFFEAB308)
    val Unclaimed = Color(0xFF6B7280)
}

object MapColors {
    /** A Pokéstop with an active lure. */
    val Lure = Color(0xFFEC4899)
    /** A stop that cannot be spun now (cooling down or out of range). */
    val Inactive = Color(0xFF6B7280)
}

object BallColors {
    val Poke = Color(0xFFEF4444)
    val Great = Color(0xFF3B82F6)
    val Ultra = Color(0xFFF59E0B)
}

/** How good a throw was; also the shrinking target ring's colour at that moment. */
object ThrowQualityColors {
    val Excellent = Color(0xFFFDE047)
    val Great = Color(0xFF93C5FD)
    val Nice = Color(0xFF86EFAC)
}

/** Egg rings by hatch distance. */
object EggColors {
    val TwoKm = Color(0xFF74C69D)
    val FiveKm = Color(0xFFF4A261)
    val Long = Color(0xFFC77DFF)
}

/** Lexi's orb while it hears you and while it answers. */
object LexiColors {
    val Listening = Color(0xFF7FB069)
    val Answering = Color(0xFF8AB4F8)
}

/** The launch animation's rune glow, matched to the red rune cube of the logo. */
object LaunchColors {
    val RuneRed = Color(0xFFE03A2F)
    val RuneEmber = Color(0xFFFF7A45)
    val StoneTop = Color(0xFF1A1512)
    val StoneBottom = Color(0xFF070605)
}

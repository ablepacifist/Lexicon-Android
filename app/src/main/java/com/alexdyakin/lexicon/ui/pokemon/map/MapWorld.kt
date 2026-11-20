package com.alexdyakin.lexicon.ui.pokemon.map

import androidx.compose.ui.graphics.Color
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The rules of the map world, kept in one place so the screen, the view model and the
 * catch screen cannot disagree about them.
 *
 * Every value here mirrors the website's `PokemonMap.js` so the two clients behave
 * identically against the same backend. Where the server also enforces a value — the
 * spin radius, for instance — the server is the authority and these are only what the
 * UI shows; they must not drift apart silently, which is why each says where it comes
 * from.
 */
object MapWorld {

    /** Matches `POLL_INTERVAL_MS` on the website. */
    const val POLL_INTERVAL_MS = 30_000L

    /**
     * How close the player must be to catch, spin, or even see a thing on the map.
     * Mirrors `INTERACTION_RANGE_M`. Beyond this the website degrades entities to
     * "sightings" rather than hiding them, and so does this client.
     */
    const val INTERACTION_RANGE_M = 200.0

    /**
     * What is asked of the server. Larger than the interaction range on purpose: the
     * extra ring is what populates the sightings list, and `GET /nearby` also seeds new
     * spawns within what it is asked for, so a tight radius would make a sparse world.
     */
    const val FETCH_RADIUS_M = 500.0

    /** Zoom 17, as `MapContainer` uses. Close enough to see a street, wide enough to see the ring. */
    const val DEFAULT_ZOOM = 17.0

    /** Team colours, taken from the website's `TEAM_COLOR`. */
    fun teamColor(team: String?): Color = when (team?.uppercase()) {
        "VALOR" -> Color(0xFFEF4444)
        "MYSTIC" -> Color(0xFF3B82F6)
        "INSTINCT" -> Color(0xFFEAB308)
        else -> Color(0xFF6B7280) // unclaimed
    }

    val TEAMS = listOf("VALOR", "MYSTIC", "INSTINCT")

    /**
     * Great-circle distance in metres.
     *
     * The same haversine the website uses, and the same one the server uses in
     * `GeospatialUtils`, so a thing shown as in range really is in range when the
     * request lands. A flat-earth approximation would disagree with the server near
     * the edge of the radius and produce "too far away" errors on things the UI said
     * were reachable.
     */
    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val p1 = lat1 * Math.PI / 180.0
        val p2 = lat2 * Math.PI / 180.0
        val dp = (lat2 - lat1) * Math.PI / 180.0
        val dl = (lng2 - lng1) * Math.PI / 180.0
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}

/** A throwable ball, in the order the website lists them. */
enum class Ball(val type: String, val label: String, val sprite: String, val tint: Color) {
    POKEBALL("POKEBALL", "Poké Ball", "pokeball_sprite.png", Color(0xFFEF4444)),
    GREAT_BALL("GREAT_BALL", "Great Ball", "greatball_sprite.png", Color(0xFF3B82F6)),
    ULTRA_BALL("ULTRA_BALL", "Ultra Ball", "ultraball_sprite.png", Color(0xFFF59E0B));

    companion object {
        fun of(type: String?): Ball =
            entries.firstOrNull { it.type.equals(type, ignoreCase = true) } ?: POKEBALL
    }
}

/**
 * Berries that change a throw. `RAZZ` and `NANAB` are what stops drop alongside
 * `PINAP`; the server accepts any of them on a catch and applies the effect itself.
 */
enum class Berry(val type: String, val label: String, val sprite: String) {
    RAZZ("RAZZ_BERRY", "Razz Berry", "razz_berry_sprite.png"),
    NANAB("NANAB_BERRY", "Nanab Berry", "nanab_berry_sprite.png"),
    PINAP("PINAP_BERRY", "Pinap Berry", "pinap_berry_sprite.png");

    companion object {
        fun of(type: String?): Berry? =
            entries.firstOrNull { it.type.equals(type, ignoreCase = true) }
    }
}

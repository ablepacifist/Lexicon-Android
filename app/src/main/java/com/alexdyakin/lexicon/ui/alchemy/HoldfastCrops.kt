package com.alexdyakin.lexicon.ui.alchemy

import androidx.compose.ui.graphics.Color
import com.alexdyakin.lexicon.data.Holdfast
import com.alexdyakin.lexicon.data.HoldfastStatus
import kotlin.math.roundToInt

// Shared status palette, matching the tone used elsewhere in the app rather than the
// web client's emoji. Reused by later stages for resource and cost chips.
internal val HoldfastOk = Color(0xFF6B8F5E)
internal val HoldfastWarn = Color(0xFFC8A24C)
internal val HoldfastDanger = Color(0xFFB4552F)

/**
 * One growable field type.
 *
 * Everything here is mirrored from alchemyServer and must not drift:
 *  - `ANNUAL_CROPS` and `SEED_COSTS` are `HoldfastManager.java:18-20`.
 *  - Harvest intervals are the `checkHarvests(...)` call sites, `HoldfastManager.java:636-642`,
 *    which is what actually runs. `BuildingConfig` agrees with them, so [harvestDaysFor]
 *    prefers the value the server sends and only falls back to these.
 */
internal data class CropKind(
    val type: String,
    val label: String,
    /** Day-of-plant for each planted field, straight off the holdfast. */
    val plantDays: (Holdfast) -> List<Int>,
    /** Gold per field to replant. Null for perennials, which replant themselves. */
    val seedCost: Int?,
    val fallbackHarvestDays: Int?,
) {
    val isAnnual: Boolean get() = seedCost != null
}

internal val HOLDFAST_CROPS = listOf(
    CropKind("wheat_field", "Wheat Fields", { it.wheatFieldPlantDays }, seedCost = 10, fallbackHarvestDays = 14),
    CropKind("rye_field", "Rye Fields", { it.ryeFieldPlantDays }, seedCost = 12, fallbackHarvestDays = 28),
    CropKind("vegetable_garden", "Vegetable Gardens", { it.vegetableGardenPlantDays }, seedCost = 8, fallbackHarvestDays = 10),
    CropKind("orchard", "Orchards", { it.orchardPlantDays }, seedCost = null, fallbackHarvestDays = 30),
    CropKind("berry_patch", "Berry Patches", { it.berryPatchPlantDays }, seedCost = null, fallbackHarvestDays = 7),
    CropKind("mushroom_cave", "Mushroom Caves", { it.mushroomCavePlantDays }, seedCost = null, fallbackHarvestDays = 21),
    // Vineyards deliberately have no harvest bar. The server does run them through
    // checkHarvests on a 90-day cycle, but with harvestFood=0 and harvestGold=0 that
    // harvest yields nothing at all (HoldfastManager.java:640) - the wine actually comes
    // from a separate `day % 7` branch (line 622). A 90-day progress bar would count
    // down to an event that gives you nothing, so we show the production rate instead.
    CropKind("vineyard", "Vineyards", { it.vineyardPlantDays }, seedCost = null, fallbackHarvestDays = null),
)

/** Prefers the server's own figure; falls back to the mirrored constant. */
internal fun harvestDaysFor(crop: CropKind, status: HoldfastStatus): Int? =
    status.buildingMenu.firstOrNull { it.type == crop.type }?.harvestDays ?: crop.fallbackHarvestDays

/** 0f..1f toward harvest. Clamped, because a field can sit past its harvest day. */
internal fun cropProgress(daysElapsed: Int, plantDay: Int, harvestDays: Int): Float {
    if (harvestDays <= 0) return 1f
    val grown = (daysElapsed - plantDay).coerceAtLeast(0)
    return (grown.toFloat() / harvestDays).coerceIn(0f, 1f)
}

internal fun cropPercent(daysElapsed: Int, plantDay: Int, harvestDays: Int): Int =
    (cropProgress(daysElapsed, plantDay, harvestDays) * 100).roundToInt()

/** Days grown, floored at 0 and capped at the harvest interval for display. */
internal fun cropGrownDays(daysElapsed: Int, plantDay: Int, harvestDays: Int): Int =
    (daysElapsed - plantDay).coerceIn(0, harvestDays)

/**
 * Built fields of this type that are not currently planted.
 *
 * Only annuals can be fallow: perennials are re-added to the plant-day list the moment
 * they are harvested (`HoldfastManager.java:663`).
 */
internal fun fallowCount(holdfast: Holdfast, crop: CropKind): Int {
    if (!crop.isAnnual) return 0
    val built = holdfast.buildings[crop.type] ?: 0
    return (built - crop.plantDays(holdfast).size).coerceAtLeast(0)
}

/** Gold the server will charge to replant every fallow field of this type. */
internal fun replantCost(holdfast: Holdfast, crop: CropKind): Int =
    fallowCount(holdfast, crop) * (crop.seedCost ?: 0)

// ── derived status figures ───────────────────────────────────────────────────

/** Severity of a displayed value, driving the chip colour. */
internal enum class HoldfastLevel { OK, WARN, DANGER }

/**
 * Food eaten per day: `ceil(population * 0.2)`, from `HoldfastManager.java:138`.
 * When stores run dry the server buys emergency rations at 2g per head instead.
 */
internal fun dailyFoodNeed(population: Int): Int =
    kotlin.math.ceil(population * 0.2).toInt()

/** Gold per day spent on rations once food hits zero (`HoldfastManager.java:148`). */
internal fun rationCost(population: Int): Double = population * 2.0

/**
 * Mirrors the server's own alarm rather than the web client's.
 *
 * alchemyServer warns at `food < foodNeeded * 14` — under a fortnight of supply
 * (`HoldfastManager.java:141`). The web uses `food < population * 2`, which is a
 * different and less meaningful line; matching the server keeps the app's warning and
 * the event log agreeing with each other.
 */
internal fun foodLevel(food: Int, population: Int): HoldfastLevel {
    if (food <= 0) return HoldfastLevel.DANGER
    return if (food < dailyFoodNeed(population) * 14) HoldfastLevel.WARN else HoldfastLevel.OK
}

/** Spoilage is worth flagging only when a batch is about to turn. */
internal fun spoilLevel(nextSpoilIn: Int): HoldfastLevel = when {
    nextSpoilIn < 0 -> HoldfastLevel.OK      // nothing stored to spoil
    nextSpoilIn <= 3 -> HoldfastLevel.DANGER
    nextSpoilIn <= 7 -> HoldfastLevel.WARN
    else -> HoldfastLevel.OK
}

internal fun happinessLevel(happiness: Double): HoldfastLevel = when {
    happiness < 25 -> HoldfastLevel.DANGER
    happiness < 50 -> HoldfastLevel.WARN
    else -> HoldfastLevel.OK
}

/** Population direction. Null means there is not enough history to call it yet. */
internal fun populationTrend(avgDailyGrowth: Double, historySize: Int): String? {
    if (historySize < 2) return null
    return when {
        avgDailyGrowth > 0.05 -> "rising"
        avgDailyGrowth < -0.05 -> "falling"
        else -> "steady"
    }
}

/** "43 (6w 1d)" — weeks are the unit the game's cycles actually run on. */
internal fun formatElapsed(days: Int): String {
    if (days < 7) return days.toString()
    val weeks = days / 7
    val rest = days % 7
    return if (rest == 0) "$days (${weeks}w)" else "$days (${weeks}w ${rest}d)"
}

/**
 * How much of a build-cost resource the holdfast actually has.
 *
 * Mirrors `HoldfastManager.getResource` (line 742). Anything outside that switch
 * returns 0 on the server too, so an unknown key reads as "you have none" rather
 * than silently passing an affordability check.
 */
internal fun Holdfast.resourceAmount(resource: String): Int = when (resource) {
    "wood" -> wood
    "stone" -> stone
    "iron" -> iron
    "food" -> food
    "beer" -> beer
    "wine" -> wine
    "tools" -> tools
    else -> 0
}

/** True when every resource in the cost map is covered. Gold is checked separately. */
internal fun Holdfast.canAfford(resourceCost: Map<String, Int>?): Boolean =
    resourceCost.orEmpty().all { (resource, needed) -> resourceAmount(resource) >= needed }

// ── food market ──────────────────────────────────────────────────────────────

/**
 * Food the market refuses to sell, kept back as a reserve.
 *
 * `HoldfastManager.java:214` computes this as `(int)(population * 0.2 * 14)` — note it
 * uses the RAW 0.2/head rate, not the rounded-up [dailyFoodNeed]. For a population of
 * 36 that is 100, while 14 days of actual consumption is 112. Mirrored exactly so the
 * figure shown matches the food that actually moves.
 */
internal fun foodMarketReserve(population: Int): Int = (population * 0.2 * 14).toInt()

/** Most a market network can shift in a day: 5 food per food_market. */
internal fun foodMarketDailyCap(marketCount: Int): Int = marketCount * 5

/** Gold from selling that much food, at the server's 0.8g per unit. */
internal fun foodMarketDailyGold(marketCount: Int): Double = foodMarketDailyCap(marketCount) * 0.8

/** What today's sale would actually be, given the reserve. */
internal fun foodMarketSellable(food: Int, population: Int, marketCount: Int): Int {
    val canSell = (food - foodMarketReserve(population)).coerceAtLeast(0)
    return minOf(canSell, foodMarketDailyCap(marketCount))
}

/** Base 30 days, plus 15 per granary (`HoldfastManager.java:90`). */
internal fun foodShelfLife(granaries: Int): Int = 30 + granaries * 15

// ── event colouring ──────────────────────────────────────────────────────────

/**
 * Classifies a server event line so the Log and Time tabs can colour it the same way.
 * Rules follow the web client's, matched against the strings the server actually emits.
 */
internal fun eventLevel(message: String): HoldfastLevel {
    val lower = message.lowercase()
    return when {
        "raid" in lower || "famine" in lower || "destroyed" in lower -> HoldfastLevel.DANGER
        "no food" in lower || "spoiled" in lower || "low!" in lower || "low! " in lower -> HoldfastLevel.WARN
        "harvested" in lower || "produced" in lower || "grew" in lower || "sold" in lower -> HoldfastLevel.OK
        else -> HoldfastLevel.OK
    }
}

/** True when the line deserves a colour at all; ordinary lines stay in the body colour. */
internal fun eventIsNotable(message: String): Boolean {
    val lower = message.lowercase()
    return "raid" in lower || "famine" in lower || "destroyed" in lower ||
        "no food" in lower || "spoiled" in lower || "low!" in lower ||
        "harvested" in lower || "produced" in lower || "grew" in lower || "sold" in lower
}

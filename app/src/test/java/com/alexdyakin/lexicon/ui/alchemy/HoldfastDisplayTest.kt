package com.alexdyakin.lexicon.ui.alchemy

import com.alexdyakin.lexicon.data.ApiUrls
import com.alexdyakin.lexicon.data.HoldfastAssetManifest
import com.alexdyakin.lexicon.data.HoldfastCalendar
import com.alexdyakin.lexicon.data.HoldfastEvent
import com.alexdyakin.lexicon.data.HoldfastStatus
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app shows what alchemyServer works out (HoldfastRules / HoldfastStatusView) and
 * calculates nothing itself. These tests pin the response shape and the display mapping.
 */
class HoldfastDisplayTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** Trimmed from a real GET /api/holdfast/{groupName} response. */
    private val statusJson = """
        {
          "holdfast": {"groupName": "party", "holdfastName": "Lockwood", "daysElapsed": 43, "gold": 120.5, "food": 0},
          "calendar": {"day": 43, "week": 7, "weeksElapsed": 6, "dayOfWeek": 1},
          "castleName": "Stone Fort",
          "populationTrend": "rising",
          "happinessLevel": "warn",
          "food": {"amount": 0, "dailyNeed": 8, "daysOfFood": 0, "level": "danger", "nextSpoilIn": 2,
                   "spoilLevel": "danger", "shelfLife": 45, "rationCostPerDay": 80.0},
          "resources": [{"key": "gold", "label": "Gold", "amount": 120.5, "level": "ok"},
                        {"key": "wood", "label": "Wood", "amount": 3, "level": "ok"}],
          "builtBuildings": [{"type": "tavern", "name": "Tavern", "count": 2}],
          "crops": [{"type": "wheat_field", "label": "Wheat Fields", "annual": true, "planted": 1, "showProgress": true,
                     "harvestDays": 14, "harvestFood": 20, "harvestGold": 0,
                     "fields": [{"field": 1, "plantDay": 36, "grownDays": 7, "harvestDays": 14, "percent": 50, "ready": false}]},
                    {"type": "vineyard", "label": "Vineyards", "annual": false, "planted": 1, "showProgress": false,
                     "harvestDays": null, "harvestFood": 0, "harvestGold": 0, "fields": []}],
          "fallowFields": [{"type": "rye_field", "label": "Rye Fields", "fallow": 2, "seedCost": 12, "replantCost": 24, "canAfford": true}],
          "foodMarket": {"available": true, "markets": 1, "enabled": true, "dailyCap": 5, "dailyGold": 4.0,
                         "pricePerFood": 0.8, "reserve": 100, "reserveDays": 14, "sellableToday": 0},
          "buildingMenu": [{"type": "guard_tower", "name": "Guard Tower", "status": "available", "cost": 25,
                            "resourceCost": {"wood": 4},
                            "resourceRequirements": [{"resource": "wood", "needed": 4, "have": 3, "enough": false}],
                            "canAffordResources": false, "canAffordGold": true, "canBuild": false}]
        }
    """.trimIndent()

    @Test
    fun `the server's status decodes with every ready-to-show part`() {
        val status = json.decodeFromString(HoldfastStatus.serializer(), statusJson)
        assertEquals(7, status.calendar.week)
        assertEquals("Stone Fort", status.castleName)
        assertEquals("rising", status.populationTrend)
        assertEquals(8, status.food.dailyNeed)
        assertEquals(80.0, status.food.rationCostPerDay, 0.001)
        assertEquals(120.5, status.resources.first().amount, 0.001)
        assertEquals("Tavern", status.builtBuildings.single().name)
        val wheat = status.crops.first()
        assertEquals(50, wheat.fields.single().percent)
        assertFalse(status.crops[1].showProgress)
        assertNull(status.crops[1].harvestDays)
        assertEquals(24, status.fallowFields.single().replantCost)
        assertEquals(100, status.foodMarket.reserve)
        val tower = status.buildingMenu.single()
        assertFalse(tower.canBuild)
        assertFalse(tower.resourceRequirements.single().enough)
        assertTrue(tower.canAffordGold)
    }

    @Test
    fun `an older status without the new parts still decodes`() {
        val status = json.decodeFromString(HoldfastStatus.serializer(), """{"holdfast": {"groupName": "x"}}""")
        assertTrue(status.crops.isEmpty())
        assertFalse(status.foodMarket.available)
        assertNull(status.populationTrend)
    }

    @Test
    fun `server levels and tones map to colours`() {
        assertEquals(HoldfastLevel.DANGER, levelOf("danger"))
        assertEquals(HoldfastLevel.WARN, levelOf("warn"))
        assertEquals(HoldfastLevel.OK, levelOf("ok"))
        assertEquals(HoldfastLevel.OK, levelOf(null))
        assertEquals(HoldfastDanger, toneColor("danger"))
        assertEquals(HoldfastWarn, toneColor("warn"))
        assertEquals(HoldfastOk, toneColor("success"))
        assertNull(toneColor("normal"))
        assertEquals("success", json.decodeFromString(HoldfastEvent.serializer(), """{"id":1,"day":2,"message":"m","tone":"success"}""").tone)
    }

    @Test
    fun `the asset manifest decodes and keys match the server's`() {
        val manifest = json.decodeFromString(
            HoldfastAssetManifest.serializer(),
            """{"version": 1, "assets": {
                 "building.granary": {"label": "Granary", "emoji": "G", "url": "/api/holdfast/assets/buildings/granary.png?v=7"},
                 "resource.food": {"label": "Food", "emoji": "F"}}}""",
        )
        assertEquals("/api/holdfast/assets/buildings/granary.png?v=7", manifest.assets["building.granary"]?.url)
        assertNull(manifest.assets["resource.food"]?.url)
        assertEquals("F", manifest.assets["resource.food"]?.emoji)

        assertEquals("building.wheat_field", buildingAssetKey("wheat_field"))
        assertEquals("resource.wood", resourceAssetKey("Wood"))
        assertEquals("castle.stone_fort", castleAssetKey("stone_fort"))
        assertEquals("tab.treasury", tabAssetKey(HoldfastTab.TREASURY))
    }

    @Test
    fun `asset paths become full alchemy urls`() {
        val url = ApiUrls.holdfastAssetUrl("/api/holdfast/assets/crops/orchard.png?v=3")
        assertEquals(ApiUrls.ALCHEMY + "api/holdfast/assets/crops/orchard.png?v=3", url)
        assertFalse(url.contains("//api"))
        assertEquals("https://cdn.example/x.png", ApiUrls.holdfastAssetUrl("https://cdn.example/x.png"))
    }

    @Test
    fun `elapsed days read from the calendar`() {
        assertEquals("6", formatElapsed(HoldfastCalendar(day = 6, week = 1, weeksElapsed = 0, dayOfWeek = 6)))
        assertEquals("7 (1w)", formatElapsed(HoldfastCalendar(day = 7, week = 2, weeksElapsed = 1, dayOfWeek = 0)))
        assertEquals("43 (6w 1d)", formatElapsed(HoldfastCalendar(day = 43, week = 7, weeksElapsed = 6, dayOfWeek = 1)))
    }
}

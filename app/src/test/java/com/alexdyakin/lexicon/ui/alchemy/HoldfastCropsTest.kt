package com.alexdyakin.lexicon.ui.alchemy

import com.alexdyakin.lexicon.data.Holdfast
import com.alexdyakin.lexicon.data.HoldfastBuildingMenuItem
import com.alexdyakin.lexicon.data.HoldfastStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the crop table against drifting from alchemyServer. The figures come from
 * `HoldfastManager.java` — `ANNUAL_CROPS`/`SEED_COSTS` at lines 18-20 and the
 * `checkHarvests(...)` call sites at 636-642.
 */
class HoldfastCropsTest {

    private fun crop(type: String) = HOLDFAST_CROPS.first { it.type == type }

    @Test
    fun `seed costs and annual flags match the server`() {
        assertEquals(10, crop("wheat_field").seedCost)
        assertEquals(12, crop("rye_field").seedCost)
        assertEquals(8, crop("vegetable_garden").seedCost)

        assertTrue(crop("wheat_field").isAnnual)
        assertTrue(crop("rye_field").isAnnual)
        assertTrue(crop("vegetable_garden").isAnnual)

        // Perennials replant themselves, so they never need seed money.
        listOf("orchard", "vineyard", "berry_patch", "mushroom_cave").forEach {
            assertFalse("$it must not be annual", crop(it).isAnnual)
            assertNull(crop(it).seedCost)
        }
    }

    @Test
    fun `fallback harvest intervals match the checkHarvests call sites`() {
        assertEquals(14, crop("wheat_field").fallbackHarvestDays)
        assertEquals(28, crop("rye_field").fallbackHarvestDays)
        assertEquals(10, crop("vegetable_garden").fallbackHarvestDays)
        assertEquals(30, crop("orchard").fallbackHarvestDays)
        assertEquals(7, crop("berry_patch").fallbackHarvestDays)
        assertEquals(21, crop("mushroom_cave").fallbackHarvestDays)
        // Vineyard's 90-day harvest yields nothing, so it gets no bar at all.
        assertNull(crop("vineyard").fallbackHarvestDays)
    }

    @Test
    fun `the server's harvestDays wins over the local fallback`() {
        val status = HoldfastStatus(
            buildingMenu = listOf(
                HoldfastBuildingMenuItem(type = "wheat_field", name = "Wheat Field", harvestDays = 21),
            ),
        )
        assertEquals(21, harvestDaysFor(crop("wheat_field"), status))
        // Not in the menu, so the mirrored constant is used.
        assertEquals(28, harvestDaysFor(crop("rye_field"), status))
    }

    @Test
    fun `crop progress is clamped at both ends`() {
        assertEquals(0f, cropProgress(daysElapsed = 5, plantDay = 5, harvestDays = 14), 0.001f)
        assertEquals(0.5f, cropProgress(daysElapsed = 12, plantDay = 5, harvestDays = 14), 0.001f)
        assertEquals(1f, cropProgress(daysElapsed = 19, plantDay = 5, harvestDays = 14), 0.001f)
        // Overripe: still full, never above.
        assertEquals(1f, cropProgress(daysElapsed = 90, plantDay = 5, harvestDays = 14), 0.001f)
        // Planted "in the future" cannot go negative.
        assertEquals(0f, cropProgress(daysElapsed = 1, plantDay = 5, harvestDays = 14), 0.001f)
        // A zero interval must not divide by zero.
        assertEquals(1f, cropProgress(daysElapsed = 3, plantDay = 1, harvestDays = 0), 0.001f)
    }

    @Test
    fun `percent and grown days track the bar`() {
        assertEquals(50, cropPercent(daysElapsed = 12, plantDay = 5, harvestDays = 14))
        assertEquals(100, cropPercent(daysElapsed = 99, plantDay = 5, harvestDays = 14))
        assertEquals(7, cropGrownDays(daysElapsed = 12, plantDay = 5, harvestDays = 14))
        assertEquals(14, cropGrownDays(daysElapsed = 99, plantDay = 5, harvestDays = 14))
        assertEquals(0, cropGrownDays(daysElapsed = 1, plantDay = 5, harvestDays = 14))
    }

    @Test
    fun `fallow is built minus planted, for annuals only`() {
        val holdfast = Holdfast(
            daysElapsed = 20,
            buildings = mapOf("wheat_field" to 5, "berry_patch" to 3),
            wheatFieldPlantDays = listOf(10, 12),
            berryPatchPlantDays = listOf(18),
        )
        assertEquals(3, fallowCount(holdfast, crop("wheat_field")))
        // Perennials are never reported fallow even when the numbers differ.
        assertEquals(0, fallowCount(holdfast, crop("berry_patch")))
        // Nothing built, nothing fallow.
        assertEquals(0, fallowCount(holdfast, crop("rye_field")))
    }

    @Test
    fun `fallow never goes negative if more is planted than built`() {
        val holdfast = Holdfast(
            buildings = mapOf("wheat_field" to 1),
            wheatFieldPlantDays = listOf(1, 2, 3),
        )
        assertEquals(0, fallowCount(holdfast, crop("wheat_field")))
    }

    @Test
    fun `replant cost is fallow count times seed price`() {
        val holdfast = Holdfast(
            buildings = mapOf("wheat_field" to 5, "vegetable_garden" to 4, "rye_field" to 2),
            wheatFieldPlantDays = listOf(1, 2),
            vegetableGardenPlantDays = emptyList(),
            ryeFieldPlantDays = listOf(1),
        )
        assertEquals(3 * 10, replantCost(holdfast, crop("wheat_field")))
        assertEquals(4 * 8, replantCost(holdfast, crop("vegetable_garden")))
        assertEquals(1 * 12, replantCost(holdfast, crop("rye_field")))
        assertEquals(0, replantCost(holdfast, crop("orchard")))
    }

    @Test
    fun `every crop the holdfast model tracks is represented`() {
        val types = HOLDFAST_CROPS.map { it.type }.toSet()
        assertEquals(
            setOf("wheat_field", "rye_field", "vegetable_garden", "orchard", "vineyard", "berry_patch", "mushroom_cave"),
            types,
        )
    }
}

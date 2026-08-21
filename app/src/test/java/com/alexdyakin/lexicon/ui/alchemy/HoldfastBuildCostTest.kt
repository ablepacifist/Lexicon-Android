package com.alexdyakin.lexicon.ui.alchemy

import com.alexdyakin.lexicon.data.Holdfast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Build-cost affordability, mirroring `HoldfastManager.getResource` (line 742). */
class HoldfastBuildCostTest {

    private val stocked = Holdfast(
        gold = 500.0,
        wood = 10, stone = 8, iron = 3,
        food = 40, beer = 5, wine = 2, tools = 6,
    )

    @Test
    fun `resource lookup covers exactly what the server maps`() {
        assertEquals(10, stocked.resourceAmount("wood"))
        assertEquals(8, stocked.resourceAmount("stone"))
        assertEquals(3, stocked.resourceAmount("iron"))
        assertEquals(40, stocked.resourceAmount("food"))
        assertEquals(5, stocked.resourceAmount("beer"))
        assertEquals(2, stocked.resourceAmount("wine"))
        assertEquals(6, stocked.resourceAmount("tools"))
    }

    @Test
    fun `an unknown resource reads as zero, matching the server default`() {
        // The server's switch falls through to 0, so an unrecognised key must never
        // look affordable.
        assertEquals(0, stocked.resourceAmount("gold"))
        assertEquals(0, stocked.resourceAmount("silver"))
        assertEquals(0, stocked.resourceAmount("mithril"))
        assertFalse(stocked.canAfford(mapOf("mithril" to 1)))
    }

    @Test
    fun `affordability needs every line of the cost covered`() {
        assertTrue(stocked.canAfford(mapOf("wood" to 4)))
        assertTrue(stocked.canAfford(mapOf("wood" to 10)))          // exactly enough
        assertTrue(stocked.canAfford(mapOf("wood" to 5, "stone" to 5)))
        assertFalse(stocked.canAfford(mapOf("wood" to 11)))
        // One short line fails the whole cost.
        assertFalse(stocked.canAfford(mapOf("wood" to 4, "iron" to 99)))
    }

    @Test
    fun `no resource cost is always affordable`() {
        assertTrue(stocked.canAfford(null))
        assertTrue(stocked.canAfford(emptyMap()))
        assertTrue(Holdfast().canAfford(emptyMap()))
    }

    @Test
    fun `effect badges drop a meaningless decimal`() {
        assertEquals("2", trimNumber(2.0))
        assertEquals("-3", trimNumber(-3.0))
        assertEquals("0", trimNumber(0.0))
        assertEquals("1.5", trimNumber(1.5))
    }
}

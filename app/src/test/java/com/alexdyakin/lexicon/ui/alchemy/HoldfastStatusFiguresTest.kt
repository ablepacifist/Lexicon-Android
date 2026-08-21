package com.alexdyakin.lexicon.ui.alchemy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Derived Status-tab figures. Every number is mirrored from alchemyServer's
 * `HoldfastManager.java`, so these tests are the drift alarm.
 */
class HoldfastStatusFiguresTest {

    @Test
    fun `daily food need is ceil of a fifth of the population`() {
        // ceil(40 * 0.2) = 8 — the figure the server subtracts each day.
        assertEquals(8, dailyFoodNeed(40))
        assertEquals(1, dailyFoodNeed(1))
        assertEquals(2, dailyFoodNeed(6))   // 1.2 rounds up
        assertEquals(8, dailyFoodNeed(37))  // 7.4 rounds up
        assertEquals(0, dailyFoodNeed(0))
    }

    @Test
    fun `ration cost is two gold a head`() {
        assertEquals(80.0, rationCost(40), 0.001)
        assertEquals(74.0, rationCost(37), 0.001)
    }

    /**
     * Deliberately the server's threshold (`food < need * 14`), not the web client's
     * `food < population * 2`. Matching the server keeps the chip and the event log
     * from disagreeing about when supplies are "low".
     */
    @Test
    fun `food warns below a fortnight of supply and is dangerous at zero`() {
        val pop = 40                       // needs 8/day, so a fortnight is 112
        assertEquals(HoldfastLevel.DANGER, foodLevel(0, pop))
        assertEquals(HoldfastLevel.WARN, foodLevel(1, pop))
        assertEquals(HoldfastLevel.WARN, foodLevel(111, pop))
        assertEquals(HoldfastLevel.OK, foodLevel(112, pop))
        assertEquals(HoldfastLevel.OK, foodLevel(500, pop))
    }

    @Test
    fun `spoilage escalates as the batch nears its end`() {
        assertEquals(HoldfastLevel.OK, spoilLevel(-1))   // nothing stored
        assertEquals(HoldfastLevel.DANGER, spoilLevel(0))
        assertEquals(HoldfastLevel.DANGER, spoilLevel(3))
        assertEquals(HoldfastLevel.WARN, spoilLevel(4))
        assertEquals(HoldfastLevel.WARN, spoilLevel(7))
        assertEquals(HoldfastLevel.OK, spoilLevel(8))
    }

    @Test
    fun `happiness bands`() {
        assertEquals(HoldfastLevel.DANGER, happinessLevel(0.0))
        assertEquals(HoldfastLevel.DANGER, happinessLevel(24.9))
        assertEquals(HoldfastLevel.WARN, happinessLevel(25.0))
        assertEquals(HoldfastLevel.WARN, happinessLevel(49.9))
        assertEquals(HoldfastLevel.OK, happinessLevel(50.0))
    }

    @Test
    fun `population trend needs two samples before it commits`() {
        assertNull(populationTrend(1.0, historySize = 0))
        assertNull(populationTrend(1.0, historySize = 1))
        assertEquals("rising", populationTrend(0.5, historySize = 2))
        assertEquals("falling", populationTrend(-0.5, historySize = 5))
        assertEquals("steady", populationTrend(0.0, historySize = 5))
        // Inside the dead band either way.
        assertEquals("steady", populationTrend(0.05, historySize = 5))
        assertEquals("steady", populationTrend(-0.05, historySize = 5))
    }

    @Test
    fun `elapsed days gain a week breakdown once there is one`() {
        assertEquals("0", formatElapsed(0))
        assertEquals("6", formatElapsed(6))
        assertEquals("7 (1w)", formatElapsed(7))
        assertEquals("43 (6w 1d)", formatElapsed(43))
        assertEquals("344 (49w 1d)", formatElapsed(344))
    }
}

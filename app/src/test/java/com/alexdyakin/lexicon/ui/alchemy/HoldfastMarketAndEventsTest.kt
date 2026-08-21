package com.alexdyakin.lexicon.ui.alchemy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Food-market economics and event colouring, mirrored from alchemyServer. */
class HoldfastMarketAndEventsTest {

    /**
     * The reserve uses the RAW 0.2/head rate, not the rounded-up daily need. For a
     * population of 36 the server holds back 100, while 14 days of real consumption
     * is 112. Getting this wrong would misreport how much is actually sellable.
     */
    @Test
    fun `market reserve mirrors the server's un-rounded formula`() {
        assertEquals(100, foodMarketReserve(36))    // (int)(36 * 0.2 * 14) = 100
        assertEquals(112, foodMarketReserve(40))    // (int)(40 * 0.2 * 14) = 112
        assertEquals(0, foodMarketReserve(0))
        // Deliberately different from a fortnight of rounded-up consumption.
        assertFalse(foodMarketReserve(36) == dailyFoodNeed(36) * 14)
    }

    @Test
    fun `daily cap and takings scale with market count`() {
        assertEquals(0, foodMarketDailyCap(0))
        assertEquals(5, foodMarketDailyCap(1))
        assertEquals(15, foodMarketDailyCap(3))
        assertEquals(4.0, foodMarketDailyGold(1), 0.001)     // 5 * 0.8
        assertEquals(12.0, foodMarketDailyGold(3), 0.001)    // 15 * 0.8
    }

    @Test
    fun `nothing sells until stores clear the reserve`() {
        val pop = 40                                   // reserve 112
        assertEquals(0, foodMarketSellable(food = 50, population = pop, marketCount = 1))
        assertEquals(0, foodMarketSellable(food = 112, population = pop, marketCount = 1))
        assertEquals(3, foodMarketSellable(food = 115, population = pop, marketCount = 1))
        // Capped by the market's own throughput.
        assertEquals(5, foodMarketSellable(food = 500, population = pop, marketCount = 1))
        assertEquals(10, foodMarketSellable(food = 500, population = pop, marketCount = 2))
        // No market, no sale.
        assertEquals(0, foodMarketSellable(food = 500, population = pop, marketCount = 0))
    }

    @Test
    fun `shelf life grows with granaries`() {
        assertEquals(30, foodShelfLife(0))
        assertEquals(45, foodShelfLife(1))
        assertEquals(75, foodShelfLife(3))
    }

    @Test
    fun `events are classified the same way wherever they are shown`() {
        assertEquals(HoldfastLevel.DANGER, eventLevel("DAY 7 - BANDIT RAID! Lost 120g"))
        assertEquals(HoldfastLevel.DANGER, eventLevel("DAY 14 - FAMINE! No food and no gold"))
        assertEquals(HoldfastLevel.DANGER, eventLevel("Buildings destroyed: Wheat Field"))
        assertEquals(HoldfastLevel.WARN, eventLevel("DAY 7 - No food! Spent 76g on emergency rations"))
        assertEquals(HoldfastLevel.WARN, eventLevel("DAY 21 - 12 food has spoiled! (shelf life: 30d)"))
        assertEquals(HoldfastLevel.OK, eventLevel("wheat field harvested! +20 food"))
        assertEquals(HoldfastLevel.OK, eventLevel("Vineyards produced 4 wine"))
        assertEquals(HoldfastLevel.OK, eventLevel("Food Market sold 5 food for 4.0g"))
    }

    @Test
    fun `only notable lines get a colour`() {
        assertTrue(eventIsNotable("BANDIT RAID!"))
        assertTrue(eventIsNotable("wheat field harvested!"))
        assertFalse(eventIsNotable("Day 10: Net +36.6g | Total: 421.0g"))
        assertFalse(eventIsNotable("Advancing 7 day(s)..."))
    }
}

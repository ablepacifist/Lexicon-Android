package com.alexdyakin.lexicon.ui.alchemy

import com.alexdyakin.lexicon.data.AdvanceHoldfastRequest
import com.alexdyakin.lexicon.data.BuildHoldfastRequest
import com.alexdyakin.lexicon.data.CreateHoldfastRequest
import com.alexdyakin.lexicon.data.DepositHoldfastRequest
import com.alexdyakin.lexicon.data.Holdfast
import com.alexdyakin.lexicon.data.HoldfastEvent
import com.alexdyakin.lexicon.data.HoldfastOperationResponse
import com.alexdyakin.lexicon.data.HoldfastStatus
import com.alexdyakin.lexicon.data.ReplantHoldfastRequest
import com.alexdyakin.lexicon.data.ToggleFoodMarketRequest
import com.alexdyakin.lexicon.data.WithdrawHoldfastRequest
import com.alexdyakin.lexicon.data.api.AlchemyApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class HoldfastViewModelTest {

    private lateinit var api: FakeHoldfastApi

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        api = FakeHoldfastApi()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `holdfast view model loads and runs the native action flow`() = runTest {
        val viewModel = HoldfastViewModel(api.proxy)
        advanceUntilIdle()

        assertEquals(1, viewModel.state.value.holdfasts.size)
        assertEquals("zx", viewModel.state.value.holdfasts.first().groupName)

        viewModel.selectHoldfast(viewModel.state.value.holdfasts.first())
        advanceUntilIdle()
        assertNotNull(viewModel.state.value.selectedStatus)
        assertEquals("Ironkeep", viewModel.state.value.selectedStatus?.holdfast?.holdfastName)

        viewModel.advance(3)
        advanceUntilIdle()
        assertEquals("Time advanced.", viewModel.state.value.notice)
        assertEquals(4, viewModel.state.value.selectedStatus?.holdfast?.daysElapsed)
        assertFalse(viewModel.state.value.lastActionEvents.isEmpty())

        viewModel.withdraw(WithdrawForm(gold = "25", beer = "1", grain = "2", tools = "0"))
        advanceUntilIdle()
        assertEquals("Resources withdrawn", viewModel.state.value.notice)

        viewModel.toggleFoodMarket()
        advanceUntilIdle()
        assertEquals("Food market toggled.", viewModel.state.value.notice)

        viewModel.deleteSelected()
        advanceUntilIdle()
        assertEquals(null, viewModel.state.value.selectedGroupName)
        assertEquals("Holdfast deleted", viewModel.state.value.notice)

        assertEquals("GET /api/holdfast/all", api.calls.first())
        assert(api.calls.contains("GET /api/holdfast/zx"))
        assert(api.calls.contains("POST /api/holdfast/advance"))
        assert(api.calls.contains("POST /api/holdfast/withdraw"))
        assert(api.calls.contains("POST /api/holdfast/toggle-food-market"))
        assert(api.calls.contains("DELETE /api/holdfast/zx"))
    }

    private class FakeHoldfastApi {
        val calls = mutableListOf<String>()
        private var holdfast = sampleHoldfast(daysElapsed = 1)

        val proxy: AlchemyApi = Proxy.newProxyInstance(
            AlchemyApi::class.java.classLoader,
            arrayOf(AlchemyApi::class.java),
        ) { _, method, args ->
            when (method.name) {
                "holdfasts" -> {
                    calls += "GET /api/holdfast/all"
                    listOf(holdfast)
                }
                "holdfast" -> {
                    val groupName = args?.get(0) as String
                    calls += "GET /api/holdfast/$groupName"
                    status(holdfast.copy(groupName = groupName, holdfastName = "Ironkeep"), emptyList())
                }
                "createHoldfast" -> {
                    val body = args?.get(0) as CreateHoldfastRequest
                    calls += "POST /api/holdfast/create"
                    holdfast = sampleHoldfast(groupName = body.groupName, holdfastName = body.holdfastName)
                    holdfast
                }
                "advanceHoldfast" -> {
                    val body = args?.get(0) as AdvanceHoldfastRequest
                    calls += "POST /api/holdfast/advance"
                    holdfast = holdfast.copy(daysElapsed = holdfast.daysElapsed + body.days, gold = holdfast.gold + 50)
                    status(holdfast, listOf("Advancing ${body.days} day(s)...", "DAY 1 - patrols returned safely"))
                }
                "buildHoldfast" -> {
                    calls += "POST /api/holdfast/build"
                    status(holdfast, emptyList())
                }
                "depositHoldfast" -> {
                    val body = args?.get(0) as DepositHoldfastRequest
                    calls += "POST /api/holdfast/deposit"
                    holdfast = holdfast.copy(gold = holdfast.gold + body.gold)
                    holdfast
                }
                "withdrawHoldfast" -> {
                    val body = args?.get(0) as WithdrawHoldfastRequest
                    calls += "POST /api/holdfast/withdraw"
                    holdfast = holdfast.copy(
                        gold = holdfast.gold - body.gold,
                        beer = holdfast.beer - body.beer,
                        wine = holdfast.wine - body.wine,
                        grain = holdfast.grain - body.grain,
                        tools = holdfast.tools - body.tools,
                    )
                    HoldfastOperationResponse(success = true, message = "Resources withdrawn")
                }
                "replantHoldfast" -> {
                    calls += "POST /api/holdfast/replant"
                    status(holdfast, emptyList())
                }
                "toggleFoodMarket" -> {
                    calls += "POST /api/holdfast/toggle-food-market"
                    holdfast = holdfast.copy(foodMarketEnabled = !holdfast.foodMarketEnabled)
                    holdfast
                }
                "holdfastEvents" -> {
                    val groupName = args?.get(0) as String
                    calls += "GET /api/holdfast/$groupName/events"
                    listOf(HoldfastEvent(id = 1, day = 1, message = "DAY 1 - patrols returned safely"))
                }
                "deleteHoldfast" -> {
                    val groupName = args?.get(0) as String
                    calls += "DELETE /api/holdfast/$groupName"
                    HoldfastOperationResponse(success = true, message = "Holdfast deleted")
                }
                "toString" -> "FakeHoldfastApi"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> proxy === args?.get(0)
                else -> error("Unexpected call: ${method.name}")
            }
        } as AlchemyApi

        private fun sampleHoldfast(
            groupName: String = "zx",
            holdfastName: String = "Ironkeep",
            daysElapsed: Int = 1,
        ) = Holdfast(
            id = 1,
            groupName = groupName,
            holdfastName = holdfastName,
            baseGoldPerDay = 40.0,
            population = 40,
            castleType = "wood_fort",
            gold = 608.7,
            silver = 0,
            happiness = 50.0,
            targetHappiness = 50.0,
            daysElapsed = daysElapsed,
            beer = 3,
            grain = 5,
            wine = 0,
            tools = 2,
            raidsSurvived = 0,
            food = 10,
            wood = 0,
            stone = 0,
            iron = 0,
            buildings = mapOf("tavern" to 1),
            foodMarketEnabled = false,
        )

        private fun status(holdfast: Holdfast, events: List<String>) = HoldfastStatus(
            holdfast = holdfast,
            message = "",
            events = events,
            dailyIncome = 44.1,
            dailyUpkeep = 1.4,
            netDailyGold = 42.7,
            protection = 47.8,
            raidChance = 4.42,
            targetHappiness = 50.0,
            buildingMenu = emptyList(),
            daysOfFood = 2,
            nextSpoilIn = 12,
            foodShelfLife = 30,
            foodMarketEnabled = holdfast.foodMarketEnabled,
            populationChange = 0,
            avgDailyGrowth = 0.0,
            populationHistory = emptyList(),
        )
    }
}
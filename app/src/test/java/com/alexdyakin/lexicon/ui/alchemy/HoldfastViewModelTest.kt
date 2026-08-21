package com.alexdyakin.lexicon.ui.alchemy

import com.alexdyakin.lexicon.data.AdvanceHoldfastRequest
import com.alexdyakin.lexicon.data.CreateHoldfastRequest
import com.alexdyakin.lexicon.data.DepositHoldfastRequest
import com.alexdyakin.lexicon.data.Holdfast
import com.alexdyakin.lexicon.data.HoldfastAdvanceResponse
import com.alexdyakin.lexicon.data.HoldfastBuildingMenuItem
import com.alexdyakin.lexicon.data.HoldfastEvent
import com.alexdyakin.lexicon.data.HoldfastMutationResponse
import com.alexdyakin.lexicon.data.HoldfastOperationResponse
import com.alexdyakin.lexicon.data.HoldfastStatus
import com.alexdyakin.lexicon.data.WithdrawHoldfastRequest
import com.alexdyakin.lexicon.data.api.AlchemyApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class HoldfastViewModelTest {

    private lateinit var api: FakeHoldfastApi

    private fun viewModel() = HoldfastViewModel(api.proxy, Json { ignoreUnknownKeys = true })

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
        val viewModel = viewModel()
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
        assertTrue(api.calls.contains("GET /api/holdfast/zx"))
        assertTrue(api.calls.contains("POST /api/holdfast/advance"))
        assertTrue(api.calls.contains("POST /api/holdfast/withdraw"))
        assertTrue(api.calls.contains("POST /api/holdfast/toggle-food-market"))
        assertTrue(api.calls.contains("DELETE /api/holdfast/zx"))
    }

    /**
     * The regression guard for the headline bug: `/advance` returns only
     * `{success, events, holdfast, ...}`, so trusting it as a full status wiped
     * protection, raid chance and the whole building menu until a later GET landed.
     */
    @Test
    fun `advance keeps the rich status instead of a hollow one`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.selectHoldfast(viewModel.state.value.holdfasts.first())
        advanceUntilIdle()

        viewModel.advance(3)
        advanceUntilIdle()

        val status = viewModel.state.value.selectedStatus
        assertNotNull(status)
        assertFalse("building menu was blanked by the advance response", status!!.buildingMenu.isEmpty())
        assertEquals(47.8, status.protection, 0.001)
        assertTrue(status.raidChance > 0.0)
    }

    @Test
    fun `build and replant re-fetch the real status`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.selectHoldfast(viewModel.state.value.holdfasts.first())
        advanceUntilIdle()
        api.calls.clear()

        viewModel.build("tavern")
        advanceUntilIdle()
        assertTrue(api.calls.contains("POST /api/holdfast/build"))
        assertTrue(
            "build must be followed by a status GET",
            api.calls.indexOf("GET /api/holdfast/zx") > api.calls.indexOf("POST /api/holdfast/build"),
        )
        assertFalse(viewModel.state.value.selectedStatus!!.buildingMenu.isEmpty())

        api.calls.clear()
        viewModel.replant("wheat_field")
        advanceUntilIdle()
        assertTrue(api.calls.contains("POST /api/holdfast/replant"))
        assertTrue(api.calls.contains("GET /api/holdfast/zx"))
    }

    @Test
    fun `status failure surfaces an error instead of hanging on a spinner`() = runTest {
        api.failWith["holdfast"] = httpError(500, "boom")
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.selectHoldfast(viewModel.state.value.holdfasts.first())
        advanceUntilIdle()

        val state = viewModel.state.value
        assertNull("no status, so the screen must show the failure view", state.selectedStatus)
        assertFalse("detailLoading must clear or the spinner never goes away", state.detailLoading)
        assertNotNull("an error must be set", state.error)
    }

    @Test
    fun `retry after a status failure recovers`() = runTest {
        api.failWith["holdfast"] = httpError(500, "boom")
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.selectHoldfast(viewModel.state.value.holdfasts.first())
        advanceUntilIdle()
        assertNull(viewModel.state.value.selectedStatus)

        api.failWith.remove("holdfast")
        viewModel.retrySelectedStatus()
        advanceUntilIdle()

        assertNotNull("retry must recover the status", viewModel.state.value.selectedStatus)
        assertNull(viewModel.state.value.error)
        // The fake records a call only once it gets past failWith, so the failed
        // attempt does not appear here; one successful GET is the recovery.
        assertEquals(1, api.calls.count { it == "GET /api/holdfast/zx" })
    }

    @Test
    fun `plain text error body reaches the user`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.selectHoldfast(viewModel.state.value.holdfasts.first())
        advanceUntilIdle()

        api.failWith["withdrawHoldfast"] = httpError(400, "Insufficient resources")
        viewModel.withdraw(WithdrawForm(gold = "9999"))
        advanceUntilIdle()

        assertEquals("Insufficient resources", viewModel.state.value.error)
    }

    @Test
    fun `json error body surfaces the inner message`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.selectHoldfast(viewModel.state.value.holdfasts.first())
        advanceUntilIdle()

        api.failWith["buildHoldfast"] = httpError(
            400,
            """{"success":false,"message":"Insufficient funds. Need: 200g, Have: 143.0g. Advance ~2 days."}""",
        )
        viewModel.build("castle_keep")
        advanceUntilIdle()

        assertEquals(
            "Insufficient funds. Need: 200g, Have: 143.0g. Advance ~2 days.",
            viewModel.state.value.error,
        )
    }

    @Test
    fun `an html error page is not shown verbatim`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.selectHoldfast(viewModel.state.value.holdfasts.first())
        advanceUntilIdle()

        api.failWith["buildHoldfast"] = httpError(502, "<html><body>Bad Gateway</body></html>")
        viewModel.build("castle_keep")
        advanceUntilIdle()

        assertEquals("Server error (HTTP 502).", viewModel.state.value.error)
    }

    /**
     * The exact offline wording is not asserted here: `java.lang.reflect.Proxy` wraps an
     * undeclared checked exception in `UndeclaredThrowableException`, so the fake cannot
     * deliver a bare `IOException` the way Retrofit does for a suspend function. What
     * matters and is testable is that a failed list load stops loading and says something.
     */
    @Test
    fun `a failing list load clears the spinner and reports an error`() = runTest {
        api.failWith["holdfasts"] = IOException("no route to host")
        val viewModel = viewModel()
        advanceUntilIdle()

        assertNotNull(viewModel.state.value.error)
        assertFalse(viewModel.state.value.listLoading)
        assertTrue(viewModel.state.value.holdfasts.isEmpty())
    }

    @Test
    fun `withdraw refuses empty and non-numeric forms without calling the server`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.selectHoldfast(viewModel.state.value.holdfasts.first())
        advanceUntilIdle()
        api.calls.clear()

        viewModel.withdraw(WithdrawForm())
        advanceUntilIdle()
        assertEquals("Enter at least one positive amount.", viewModel.state.value.error)
        assertFalse(api.calls.contains("POST /api/holdfast/withdraw"))

        viewModel.withdraw(WithdrawForm(gold = "abc"))
        advanceUntilIdle()
        assertEquals("Enter at least one positive amount.", viewModel.state.value.error)
        assertFalse(api.calls.contains("POST /api/holdfast/withdraw"))

        viewModel.withdraw(WithdrawForm(gold = "0", beer = "0"))
        advanceUntilIdle()
        assertFalse("all-zero is not a withdrawal", api.calls.contains("POST /api/holdfast/withdraw"))
    }

    @Test
    fun `advancing invalidates the event log`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.selectHoldfast(viewModel.state.value.holdfasts.first())
        advanceUntilIdle()

        viewModel.setTab(HoldfastTab.LOG)
        advanceUntilIdle()
        assertEquals(1, api.calls.count { it == "GET /api/holdfast/zx/events" })

        viewModel.advance(1)
        advanceUntilIdle()
        assertEquals(
            "the log must be refetched after time moves",
            2,
            api.calls.count { it == "GET /api/holdfast/zx/events" },
        )
    }

    @Test
    fun `closing a selection clears the banners`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.selectHoldfast(viewModel.state.value.holdfasts.first())
        advanceUntilIdle()

        api.failWith["buildHoldfast"] = httpError(400, "Insufficient resources")
        viewModel.build("castle_keep")
        advanceUntilIdle()
        assertNotNull(viewModel.state.value.error)

        viewModel.closeSelection()
        assertNull("an error from the detail screen must not follow you to the list", viewModel.state.value.error)
        assertNull(viewModel.state.value.notice)
    }

    @Test
    fun `a successful deposit asks the form to reset`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.selectHoldfast(viewModel.state.value.holdfasts.first())
        advanceUntilIdle()
        val before = viewModel.state.value.formResetToken

        viewModel.deposit("50")
        advanceUntilIdle()

        assertTrue(viewModel.state.value.formResetToken > before)
    }

    // ── pure helpers ─────────────────────────────────────────────────────────

    @Test
    fun `gold formatting does not follow a comma-decimal locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("143.0", formatGold(143.0))
            assertEquals("12.5", formatGold(12.45))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `food and spoilage sentinels read as words`() {
        assertEquals("plenty", formatDaysOfFood(999))
        assertEquals("plenty", formatDaysOfFood(1200))
        assertEquals("3 day(s)", formatDaysOfFood(3))
        assertEquals("—", formatSpoilIn(-1))
        assertEquals("0 day(s)", formatSpoilIn(0))
        assertEquals("12 day(s)", formatSpoilIn(12))
    }

    @Test
    fun `withdraw form validation accepts only real positive amounts`() {
        assertFalse(WithdrawForm().isValid)
        assertFalse(WithdrawForm(gold = "abc").isValid)
        assertFalse(WithdrawForm(gold = "0", beer = "0").isValid)
        assertFalse("a bad field invalidates the whole form", WithdrawForm(gold = "5", beer = "x").isValid)
        assertTrue(WithdrawForm(gold = "5").isValid)
        assertTrue(WithdrawForm(beer = "2", wine = "0").isValid)
    }

    private fun httpError(code: Int, body: String) = HttpException(
        Response.error<Any>(code, body.toResponseBody("application/json".toMediaTypeOrNull())),
    )

    private class FakeHoldfastApi {
        val calls = mutableListOf<String>()

        /** Method name → throwable, so a test can make one endpoint fail. */
        val failWith = mutableMapOf<String, Throwable>()

        private var holdfast = sampleHoldfast(daysElapsed = 1)

        val proxy: AlchemyApi = Proxy.newProxyInstance(
            AlchemyApi::class.java.classLoader,
            arrayOf(AlchemyApi::class.java),
        ) { _, method, args ->
            failWith[method.name]?.let { throw it }
            when (method.name) {
                "holdfasts" -> {
                    calls += "GET /api/holdfast/all"
                    listOf(holdfast)
                }
                "holdfast" -> {
                    val groupName = args?.get(0) as String
                    calls += "GET /api/holdfast/$groupName"
                    status(holdfast.copy(groupName = groupName, holdfastName = "Ironkeep"))
                }
                "createHoldfast" -> {
                    val body = args?.get(0) as CreateHoldfastRequest
                    calls += "POST /api/holdfast/create"
                    holdfast = sampleHoldfast(groupName = body.groupName, holdfastName = body.holdfastName)
                    holdfast
                }
                // Returns the server's real narrow shape, so the test can no longer
                // pretend advance hands back a full status.
                "advanceHoldfast" -> {
                    val body = args?.get(0) as AdvanceHoldfastRequest
                    calls += "POST /api/holdfast/advance"
                    holdfast = holdfast.copy(daysElapsed = holdfast.daysElapsed + body.days, gold = holdfast.gold + 50)
                    HoldfastAdvanceResponse(
                        success = true,
                        events = listOf("Advancing ${body.days} day(s)...", "DAY 1 - patrols returned safely"),
                        holdfast = holdfast,
                        dailyIncome = 44.1,
                        dailyUpkeep = 1.4,
                    )
                }
                "buildHoldfast" -> {
                    calls += "POST /api/holdfast/build"
                    HoldfastMutationResponse(success = true, message = "Built Tavern for 100g", holdfast = holdfast, cost = 100)
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
                    HoldfastMutationResponse(success = true, message = "Replanted 3 wheat field(s)", holdfast = holdfast)
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

        /** Only `GET /{groupName}` carries the computed fields. */
        private fun status(holdfast: Holdfast) = HoldfastStatus(
            holdfast = holdfast,
            message = "",
            events = emptyList(),
            dailyIncome = 44.1,
            dailyUpkeep = 1.4,
            netDailyGold = 42.7,
            protection = 47.8,
            raidChance = 4.42,
            targetHappiness = 50.0,
            buildingMenu = listOf(
                HoldfastBuildingMenuItem(
                    type = "tavern",
                    name = "Tavern",
                    description = "Keeps the settlement cheerful.",
                    current = 1,
                    max = 3,
                    status = "available",
                    cost = 100,
                ),
            ),
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

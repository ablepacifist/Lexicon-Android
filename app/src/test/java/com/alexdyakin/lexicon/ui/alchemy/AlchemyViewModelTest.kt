package com.alexdyakin.lexicon.ui.alchemy

import com.alexdyakin.lexicon.data.BrewPotionRequest
import com.alexdyakin.lexicon.data.BrewPotionResponse
import com.alexdyakin.lexicon.data.Effect
import com.alexdyakin.lexicon.data.ForageResponse
import com.alexdyakin.lexicon.data.Ingredient
import com.alexdyakin.lexicon.data.Inventory
import com.alexdyakin.lexicon.data.Potion
import com.alexdyakin.lexicon.data.api.AlchemyApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class AlchemyViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var api: FakeAlchemyApi

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        api = FakeAlchemyApi()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = AlchemyViewModel(api.proxy) { 7 }

    // ── foraging ─────────────────────────────────────────────

    @Test
    fun `forage asks whether Alex said yes before doing anything`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.requestForage()
        assertTrue(vm.state.value.askingForagePermission)
        assertFalse("nothing is foraged by the button alone", api.calls.contains("forage"))
    }

    @Test
    fun `answering no does not forage`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.requestForage()
        vm.answerForagePermission(alexSaidYes = false)
        advanceUntilIdle()

        assertFalse(vm.state.value.askingForagePermission)
        assertFalse(api.calls.contains("forage"))
        assertNull(vm.state.value.outcome)
        assertEquals("No foraging until Alex says so.", vm.state.value.message)
    }

    @Test
    fun `answering yes forages and keeps the result on screen until closed`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.requestForage()
        vm.answerForagePermission(alexSaidYes = true)
        advanceUntilIdle()

        assertEquals(listOf(7), api.foragedFor)
        val outcome = vm.state.value.outcome!!
        assertEquals(OutcomeKind.FORAGED, outcome.kind)
        assertEquals("Moonpetal", outcome.name)
        assertEquals("You now have 3.", outcome.detail)
        assertEquals(listOf("Glow"), outcome.effects.map { it.title })
        assertEquals(ForagePhase.Idle, vm.state.value.foragePhase)
        assertEquals("Foraged Moonpetal", vm.state.value.recent.first().text)

        vm.dismissOutcome()
        assertNull(vm.state.value.outcome)
        assertEquals("the log keeps it after the dialog closes", 1, vm.state.value.recent.size)
    }

    // ── brewing ──────────────────────────────────────────────

    @Test
    fun `picking ingredients fills two slots and a third replaces the oldest`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.toggleIngredient(1)
        assertFalse(vm.state.value.canBrew)
        vm.toggleIngredient(2)
        assertTrue(vm.state.value.canBrew)
        vm.toggleIngredient(3)
        assertEquals(2, vm.state.value.firstIngredientId)
        assertEquals(3, vm.state.value.secondIngredientId)
        vm.toggleIngredient(2)
        assertEquals("un-picking the first moves the second up", 3, vm.state.value.firstIngredientId)
        assertNull(vm.state.value.secondIngredientId)
        vm.clearBrewSelection()
        assertNull(vm.state.value.firstIngredientId)
    }

    @Test
    fun `a successful brew shows the potion the server made`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.toggleIngredient(1)
        vm.toggleIngredient(2)
        vm.brew()
        advanceUntilIdle()

        assertEquals(BrewPotionRequest(7, 1, 2), api.brewed.single())
        val outcome = vm.state.value.outcome!!
        assertEquals(OutcomeKind.BREWED, outcome.kind)
        assertEquals("Potion of Glow", outcome.name)
        assertEquals("You shine faintly.", outcome.detail)
        assertEquals(listOf("Glow"), outcome.effects.map { it.title })
        assertNull("selection is cleared for the next brew", vm.state.value.firstIngredientId)
        assertEquals("Brewed Potion of Glow from Moonpetal and Ash", vm.state.value.recent.first().text)
    }

    @Test
    fun `a failed brew is shown as a result too, with the server's reason`() = runTest(dispatcher) {
        api.brewFails = httpError(400, "Potion brewing failed.")
        val vm = viewModel()
        advanceUntilIdle()

        vm.toggleIngredient(1)
        vm.toggleIngredient(3)
        vm.brew()
        advanceUntilIdle()

        val outcome = vm.state.value.outcome!!
        assertEquals(OutcomeKind.BREW_FAILED, outcome.kind)
        assertEquals("Moonpetal + Thornroot", outcome.name)
        assertEquals("Potion brewing failed.", outcome.detail)
        assertFalse(vm.state.value.brewing)
    }

    @Test
    fun `an html error page is replaced by a plain reason`() = runTest(dispatcher) {
        api.brewFails = httpError(400, "<html><body>Bad Request</body></html>")
        val vm = viewModel()
        advanceUntilIdle()
        vm.toggleIngredient(1)
        vm.toggleIngredient(2)
        vm.brew()
        advanceUntilIdle()

        assertTrue(vm.state.value.outcome!!.detail.startsWith("These two don't share an effect"))
    }

    @Test
    fun `brew does nothing until two ingredients are chosen`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.toggleIngredient(1)
        vm.brew()
        advanceUntilIdle()
        assertTrue(api.brewed.isEmpty())
    }

    private fun httpError(code: Int, body: String) = HttpException(
        Response.error<Any>(code, body.toResponseBody("text/plain".toMediaTypeOrNull())),
    )

    private class FakeAlchemyApi {
        val calls = mutableListOf<String>()
        val foragedFor = mutableListOf<Int>()
        val brewed = mutableListOf<BrewPotionRequest>()
        var brewFails: Throwable? = null
        private var moonpetal = 2

        private fun inventory() = Inventory(
            ingredients = listOf(
                Ingredient(1, "Moonpetal", moonpetal, listOf(Effect(1, "Glow", "Faint light"))),
                Ingredient(2, "Ash", 1),
                Ingredient(3, "Thornroot", 4),
            ),
            potions = emptyList(),
        )

        val proxy: AlchemyApi = Proxy.newProxyInstance(
            AlchemyApi::class.java.classLoader,
            arrayOf(AlchemyApi::class.java),
        ) { _, method, args ->
            calls += method.name
            when (method.name) {
                "inventory" -> inventory()
                "forage" -> {
                    foragedFor += args!![0] as Int
                    moonpetal += 1
                    ForageResponse("Moonpetal")
                }
                "brewPotion" -> {
                    brewFails?.let { throw it }
                    brewed += args!![0] as BrewPotionRequest
                    BrewPotionResponse(
                        message = "Potion brewed successfully",
                        potion = Potion(id = 9, name = "Potion of Glow", quantity = 1, description = "You shine faintly.",
                            effects = listOf(Effect(1, "Glow", "Faint light"))),
                    )
                }
                "consumeIngredient", "consumePotion" -> Unit
                "toString" -> "FakeAlchemyApi"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> false
                else -> error("Unexpected call: ${method.name}")
            }
        } as AlchemyApi
    }
}

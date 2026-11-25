package com.alexdyakin.lexicon.ui.alchemy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexdyakin.lexicon.data.ConsumeIngredientRequest
import com.alexdyakin.lexicon.data.ConsumePotionRequest
import com.alexdyakin.lexicon.data.BrewPotionRequest
import com.alexdyakin.lexicon.data.Effect
import com.alexdyakin.lexicon.data.Inventory
import com.alexdyakin.lexicon.data.TokenStore
import com.alexdyakin.lexicon.data.api.AlchemyApi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException
import javax.inject.Inject

/** Where the forage animation currently is. */
enum class ForagePhase { Idle, Searching }

enum class OutcomeKind { FORAGED, BREWED, BREW_FAILED }

/**
 * The result of a forage or a brew. It stays on screen until the player closes it: a
 * snackbar was too easy to miss, and finding out what you got is the point of the action.
 */
data class AlchemyOutcome(
    val kind: OutcomeKind,
    val name: String,
    val detail: String = "",
    val effects: List<Effect> = emptyList(),
)

/** One line of what happened this visit, newest first. */
data class AlchemyLogLine(val text: String, val kind: OutcomeKind? = null)

data class AlchemyUiState(
    val loading: Boolean = true,
    val inventory: Inventory = Inventory(),
    val foragePhase: ForagePhase = ForagePhase.Idle,
    /** True while the "Did Alex say you could forage?" question is open. */
    val askingForagePermission: Boolean = false,
    val outcome: AlchemyOutcome? = null,
    val recent: List<AlchemyLogLine> = emptyList(),
    val firstIngredientId: Int? = null,
    val secondIngredientId: Int? = null,
    val brewing: Boolean = false,
    val error: String? = null,
    val message: String? = null,
) {
    val canBrew: Boolean get() = firstIngredientId != null && secondIngredientId != null && !brewing
    fun isSelected(ingredientId: Int) = ingredientId == firstIngredientId || ingredientId == secondIngredientId
}

@HiltViewModel
class AlchemyViewModel internal constructor(
    private val alchemyApi: AlchemyApi,
    private val playerIdOf: () -> Int,
) : ViewModel() {

    @Inject
    constructor(alchemyApi: AlchemyApi, tokenStore: TokenStore) : this(alchemyApi, { tokenStore.userId })

    private val _state = MutableStateFlow(AlchemyUiState())
    val state: StateFlow<AlchemyUiState> = _state.asStateFlow()

    private val playerId: Int get() = playerIdOf()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch { reload() }
    }

    private suspend fun reload() {
        _state.value = _state.value.copy(loading = true, error = null)
        try {
            val inventory = alchemyApi.inventory(playerId)
            val ids = inventory.ingredients.map { it.id }.toSet()
            _state.value = _state.value.copy(
                loading = false,
                inventory = inventory,
                // A used-up ingredient can no longer be part of the next brew.
                firstIngredientId = _state.value.firstIngredientId?.takeIf { it in ids },
                secondIngredientId = _state.value.secondIngredientId?.takeIf { it in ids },
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                loading = false,
                error = "Could not load inventory. ${e.message ?: ""}".trim(),
            )
        }
    }

    // ── Foraging ─────────────────────────────────────────────

    /**
     * The Forage button only asks first. Foraging is up to the DM, so the app checks with the
     * player before it spends a forage. This question is deliberately decided in the app.
     */
    fun requestForage() {
        if (_state.value.foragePhase != ForagePhase.Idle) return
        _state.value = _state.value.copy(askingForagePermission = true)
    }

    fun answerForagePermission(alexSaidYes: Boolean) {
        _state.value = _state.value.copy(askingForagePermission = false)
        if (alexSaidYes) {
            forage()
        } else {
            _state.value = _state.value.copy(message = "No foraging until Alex says so.")
        }
    }

    private fun forage() {
        if (_state.value.foragePhase != ForagePhase.Idle) return

        viewModelScope.launch {
            _state.value = _state.value.copy(foragePhase = ForagePhase.Searching, error = null, message = null)

            try {
                val response = alchemyApi.forage(playerId)

                // Let the searching animation play out even on a fast response —
                // snapping straight to the result reads as a glitch.
                delay(MIN_SEARCH_MS)

                reload()
                val name = response.ingredient.ifBlank { "Nothing useful" }
                val held = _state.value.inventory.ingredients.firstOrNull { it.name == name }
                _state.value = _state.value.copy(
                    foragePhase = ForagePhase.Idle,
                    outcome = AlchemyOutcome(
                        kind = OutcomeKind.FORAGED,
                        name = name,
                        detail = held?.let { "You now have ${it.quantity}." }.orEmpty(),
                        effects = held?.effects.orEmpty(),
                    ),
                )
                log("Foraged $name", OutcomeKind.FORAGED)
            } catch (e: Exception) {
                delay(MIN_SEARCH_MS)
                _state.value = _state.value.copy(
                    foragePhase = ForagePhase.Idle,
                    error = "Foraging failed. ${e.message ?: ""}".trim(),
                )
            }
        }
    }

    // ── Brewing ──────────────────────────────────────────────

    /** Brew on an ingredient: fills the first empty slot, or clears it if already chosen. */
    fun toggleIngredient(ingredientId: Int) {
        val s = _state.value
        _state.value = when {
            s.firstIngredientId == ingredientId -> s.copy(firstIngredientId = s.secondIngredientId, secondIngredientId = null)
            s.secondIngredientId == ingredientId -> s.copy(secondIngredientId = null)
            s.firstIngredientId == null -> s.copy(firstIngredientId = ingredientId)
            s.secondIngredientId == null -> s.copy(secondIngredientId = ingredientId)
            else -> s.copy(firstIngredientId = s.secondIngredientId, secondIngredientId = ingredientId)
        }
    }

    fun clearBrewSelection() {
        _state.value = _state.value.copy(firstIngredientId = null, secondIngredientId = null)
    }

    fun brew() {
        val s = _state.value
        val first = s.firstIngredientId ?: return
        val second = s.secondIngredientId ?: return
        if (s.brewing) return
        val firstName = nameOf(first)
        val secondName = nameOf(second)

        viewModelScope.launch {
            _state.value = _state.value.copy(brewing = true, error = null, message = null)
            try {
                val response = alchemyApi.brewPotion(BrewPotionRequest(playerId, first, second))
                val potion = response.potion
                val name = potion?.name?.ifBlank { null } ?: "A new potion"
                _state.value = _state.value.copy(
                    brewing = false,
                    firstIngredientId = null,
                    secondIngredientId = null,
                    outcome = AlchemyOutcome(
                        kind = OutcomeKind.BREWED,
                        name = name,
                        detail = potion?.description?.ifBlank { null } ?: response.message,
                        effects = potion?.effects.orEmpty(),
                    ),
                )
                log("Brewed $name from $firstName and $secondName", OutcomeKind.BREWED)
                reload()
            } catch (e: HttpException) {
                val backendMessage = runCatching { e.response()?.errorBody()?.string() }
                    .getOrNull()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() && !it.startsWith("<") }
                val reason = backendMessage ?: if (e.code() == 400) {
                    "These two don't share an effect. Try ingredients with an effect in common."
                } else {
                    "The brew failed (HTTP ${e.code()})."
                }
                _state.value = _state.value.copy(
                    brewing = false,
                    outcome = AlchemyOutcome(OutcomeKind.BREW_FAILED, "$firstName + $secondName", reason),
                )
                log("Failed to brew $firstName + $secondName", OutcomeKind.BREW_FAILED)
                reload()
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    brewing = false,
                    error = "Brewing failed. ${e.message ?: "Check your connection."}".trim(),
                )
            }
        }
    }

    fun dismissOutcome() {
        _state.value = _state.value.copy(outcome = null)
    }

    // ── Eating and drinking ──────────────────────────────────

    fun consume(ingredientId: Int, name: String) {
        viewModelScope.launch {
            try {
                alchemyApi.consumeIngredient(
                    ConsumeIngredientRequest(playerId = playerId, ingredientId = ingredientId)
                )
                _state.value = _state.value.copy(message = "Ate $name")
                log("Ate $name")
                reload()
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    error = "Could not eat $name. ${e.message ?: ""}".trim()
                )
            }
        }
    }

    fun consumePotion(potionId: Int, name: String) {
        viewModelScope.launch {
            try {
                alchemyApi.consumePotion(ConsumePotionRequest(playerId, potionId))
                _state.value = _state.value.copy(message = "Drank $name")
                log("Drank $name")
                reload()
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = "Could not drink $name. ${e.message ?: ""}".trim())
            }
        }
    }

    fun clearMessages() {
        _state.value = _state.value.copy(error = null, message = null)
    }

    private fun nameOf(ingredientId: Int): String =
        _state.value.inventory.ingredients.firstOrNull { it.id == ingredientId }?.name ?: "ingredient #$ingredientId"

    private fun log(text: String, kind: OutcomeKind? = null) {
        _state.value = _state.value.copy(recent = (listOf(AlchemyLogLine(text, kind)) + _state.value.recent).take(RECENT_MAX))
    }

    private companion object {
        const val MIN_SEARCH_MS = 1400L
        const val RECENT_MAX = 6
    }
}

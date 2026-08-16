package com.alexdyakin.lexicon.ui.alchemy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexdyakin.lexicon.data.ConsumeIngredientRequest
import com.alexdyakin.lexicon.data.ConsumePotionRequest
import com.alexdyakin.lexicon.data.BrewPotionRequest
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
enum class ForagePhase { Idle, Searching, Found }

data class AlchemyUiState(
    val loading: Boolean = true,
    val inventory: Inventory = Inventory(),
    val foragePhase: ForagePhase = ForagePhase.Idle,
    val foundIngredient: String? = null,
    val error: String? = null,
    val message: String? = null,
)

@HiltViewModel
class AlchemyViewModel @Inject constructor(
    private val alchemyApi: AlchemyApi,
    private val tokenStore: TokenStore,
) : ViewModel() {

    private val _state = MutableStateFlow(AlchemyUiState())
    val state: StateFlow<AlchemyUiState> = _state.asStateFlow()

    private val playerId: Int get() = tokenStore.userId

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val inventory = alchemyApi.inventory(playerId)
                _state.value = _state.value.copy(loading = false, inventory = inventory)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = "Could not load inventory. ${e.message ?: ""}".trim(),
                )
            }
        }
    }

    fun forage() {
        if (_state.value.foragePhase != ForagePhase.Idle) return

        viewModelScope.launch {
            _state.value = _state.value.copy(
                foragePhase = ForagePhase.Searching,
                foundIngredient = null,
                error = null,
                message = null,
            )

            try {
                val response = alchemyApi.forage(playerId)

                // Let the searching animation play out even on a fast response —
                // snapping straight to the result reads as a glitch.
                delay(MIN_SEARCH_MS)

                _state.value = _state.value.copy(
                    foragePhase = ForagePhase.Found,
                    foundIngredient = response.ingredient.ifBlank { "Nothing useful" },
                )
                refresh()
            } catch (e: Exception) {
                delay(MIN_SEARCH_MS)
                _state.value = _state.value.copy(
                    foragePhase = ForagePhase.Idle,
                    error = "Foraging failed. ${e.message ?: ""}".trim(),
                )
            }
        }
    }

    fun dismissForageResult() {
        _state.value = _state.value.copy(foragePhase = ForagePhase.Idle, foundIngredient = null)
    }

    fun consume(ingredientId: Int, name: String) {
        viewModelScope.launch {
            try {
                alchemyApi.consumeIngredient(
                    ConsumeIngredientRequest(playerId = playerId, ingredientId = ingredientId)
                )
                _state.value = _state.value.copy(message = "Consumed $name")
                refresh()
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    error = "Could not consume $name. ${e.message ?: ""}".trim()
                )
            }
        }
    }

    fun consumePotion(potionId: Int, name: String) {
        viewModelScope.launch {
            try {
                alchemyApi.consumePotion(ConsumePotionRequest(playerId, potionId))
                _state.value = _state.value.copy(message = "Consumed $name")
                refresh()
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = "Could not consume $name. ${e.message ?: ""}".trim())
            }
        }
    }

    fun brew(firstId: Int, secondId: Int) {
        if (firstId == secondId) {
            _state.value = _state.value.copy(error = "Choose two different ingredients.")
            return
        }
        viewModelScope.launch {
            try {
                val response = alchemyApi.brewPotion(BrewPotionRequest(playerId, firstId, secondId))
                _state.value = _state.value.copy(message = response.message.ifBlank { "Potion brewed successfully." })
                refresh()
            } catch (e: HttpException) {
                val backendMessage = runCatching { e.response()?.errorBody()?.string() }
                    .getOrNull()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                val fallback = if (e.code() == 400) {
                    "Potion brewing failed. Try ingredients with a shared effect."
                } else {
                    "Brewing failed (HTTP ${e.code()})."
                }
                _state.value = _state.value.copy(error = backendMessage ?: fallback)
                refresh()
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = "Brewing failed. ${e.message ?: "Try ingredients with a shared effect."}".trim())
            }
        }
    }

    fun clearMessages() {
        _state.value = _state.value.copy(error = null, message = null)
    }

    private companion object {
        const val MIN_SEARCH_MS = 1400L
    }
}

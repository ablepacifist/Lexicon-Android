package com.alexdyakin.lexicon.ui.pokemon

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.alexdyakin.lexicon.R
import com.alexdyakin.lexicon.data.ApiResult
import com.alexdyakin.lexicon.data.ApiUrls
import com.alexdyakin.lexicon.data.BuddyPokemonRequest
import com.alexdyakin.lexicon.data.CaughtPokemon
import com.alexdyakin.lexicon.data.EvolutionOption
import com.alexdyakin.lexicon.data.EvolvePokemonRequest
import com.alexdyakin.lexicon.data.HealPokemonRequest
import com.alexdyakin.lexicon.data.FavouritePokemonRequest
import com.alexdyakin.lexicon.data.NicknamePokemonRequest
import com.alexdyakin.lexicon.data.api.PokemonApi
import com.alexdyakin.lexicon.data.safeApiCall
import com.alexdyakin.lexicon.ui.components.EmptyBox
import com.alexdyakin.lexicon.ui.components.LoadingBox
import com.alexdyakin.lexicon.ui.components.ScreenScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PokemonDetailUiState(val loading: Boolean = true, val saving: Boolean = false, val pokemon: CaughtPokemon? = null, val favourite: Boolean = false, val evolutions: List<EvolutionOption> = emptyList(), val message: String? = null)

@HiltViewModel
class PokemonDetailViewModel @Inject constructor(private val api: PokemonApi) : ViewModel() {
    private val _state = MutableStateFlow(PokemonDetailUiState())
    val state = _state.asStateFlow()
    fun load(id: Long) = viewModelScope.launch {
        val pokemon = safeApiCall { api.collection() }.successOrNull?.firstOrNull { it.id == id }
        val evolutions = pokemon?.let { safeApiCall { api.evolutionOptions(it.id) }.successOrNull }.orEmpty()
        _state.value = PokemonDetailUiState(false, pokemon = pokemon, evolutions = evolutions, message = if (pokemon == null) "That Pokémon was not found." else null)
    }
    fun nickname(name: String) = action(call = { pokemon -> api.nickname(NicknamePokemonRequest(pokemon.id, name)) }, update = { pokemon -> pokemon.copy(nickname = name) })
    fun favourite() = viewModelScope.launch {
        val pokemon = _state.value.pokemon ?: return@launch
        _state.value = _state.value.copy(saving = true, message = null)
        when (val result = safeApiCall { api.favourite(FavouritePokemonRequest(pokemon.id, !_state.value.favourite)) }) {
            is ApiResult.Success -> _state.value = _state.value.copy(saving = false, favourite = !_state.value.favourite, message = "Saved.")
            is ApiResult.Failure -> _state.value = _state.value.copy(saving = false, message = result.message)
            ApiResult.Unauthorized -> _state.value = _state.value.copy(saving = false, message = "Sign in again to change this Pokémon.")
        }
    }
    fun buddy() = action(call = { pokemon -> api.setBuddy(BuddyPokemonRequest(pokemon.id)) }, update = { it })
    fun evolve(option: EvolutionOption) = viewModelScope.launch {
        val pokemon = _state.value.pokemon ?: return@launch
        _state.value = _state.value.copy(saving = true, message = null)
        when (val result = safeApiCall { api.evolve(pokemon.id, EvolvePokemonRequest(option.evolvesToId)) }) {
            is ApiResult.Success -> _state.value = _state.value.copy(saving = false, pokemon = pokemon.copy(speciesId = result.data.newSpeciesId, speciesName = result.data.newName, spriteKey = result.data.newSpriteKey), evolutions = emptyList(), message = "${result.data.oldName} evolved into ${result.data.newName}!")
            is ApiResult.Failure -> _state.value = _state.value.copy(saving = false, message = result.message)
            ApiResult.Unauthorized -> _state.value = _state.value.copy(saving = false, message = "Sign in again to evolve Pokémon.")
        }
    }
    fun heal(item: String) = viewModelScope.launch {
        val pokemon = _state.value.pokemon ?: return@launch
        _state.value = _state.value.copy(saving = true, message = null)
        when (val result = safeApiCall { api.heal(HealPokemonRequest(pokemon.id, item)) }) {
            is ApiResult.Success -> _state.value = _state.value.copy(saving = false, pokemon = pokemon.copy(currentHp = result.data.currentHp, hp = result.data.maxHp), message = result.data.message)
            is ApiResult.Failure -> _state.value = _state.value.copy(saving = false, message = result.message)
            ApiResult.Unauthorized -> _state.value = _state.value.copy(saving = false, message = "Sign in again to use healing items.")
        }
    }
    private fun action(call: suspend (CaughtPokemon) -> Unit, update: (CaughtPokemon) -> CaughtPokemon) = viewModelScope.launch {
        val pokemon = _state.value.pokemon ?: return@launch
        _state.value = _state.value.copy(saving = true, message = null)
        when (val result = safeApiCall { call(pokemon) }) {
            is ApiResult.Success -> _state.value = _state.value.copy(saving = false, pokemon = update(pokemon), message = "Saved.")
            is ApiResult.Failure -> _state.value = _state.value.copy(saving = false, message = result.message)
            ApiResult.Unauthorized -> _state.value = _state.value.copy(saving = false, message = "Sign in again to change this Pokémon.")
        }
    }
}

@Composable
fun PokemonDetailScreen(id: Long, onBack: () -> Unit, viewModel: PokemonDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    androidx.compose.runtime.LaunchedEffect(id) { viewModel.load(id) }
    var nickname by remember(state.pokemon?.id) { mutableStateOf(state.pokemon?.nickname.orEmpty()) }
    ScreenScaffold("Pokémon details", onBack, R.drawable.bg_dashboard) { padding ->
        when {
            state.loading -> LoadingBox(padding)
            state.pokemon == null -> EmptyBox(padding, state.message ?: "Could not load Pokémon.")
            else -> {
                val pokemon = state.pokemon!!
                Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    AsyncImage(ApiUrls.spriteUrl(pokemon.spriteKey), pokemon.displayName, Modifier.size(160.dp), contentScale = ContentScale.Fit)
                    Text(pokemon.displayName, style = MaterialTheme.typography.headlineMedium)
                    Text("Lv ${pokemon.pokemonLevel} · ${pokemon.type1} ${pokemon.type2}".trim())
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("HP ${pokemon.currentHp} / ${pokemon.hp}"); Text("Attack ${pokemon.attack} · Defense ${pokemon.defense} · Speed ${pokemon.speed}") } }
                    OutlinedTextField(nickname, { nickname = it }, label = { Text("Nickname") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ viewModel.nickname(nickname) }, enabled = !state.saving) { Text("Save name") }
                        Button({ viewModel.favourite() }, enabled = !state.saving) { Text(if (state.favourite) "Unfavourite" else "Favourite") }
                        Button({ viewModel.buddy() }, enabled = !state.saving) { Text("Set buddy") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ viewModel.heal("POTION") }, enabled = !state.saving && !pokemon.fainted && pokemon.currentHp < pokemon.hp) { Text("Use potion") }
                        Button({ viewModel.heal("REVIVE") }, enabled = !state.saving && pokemon.fainted) { Text("Use revive") }
                    }
                    if (state.evolutions.isNotEmpty()) Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                        Text("Evolution", style = MaterialTheme.typography.titleMedium)
                        state.evolutions.forEach { option -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text("Evolve into ${option.evolvesToName}"); Text(option.reason, style = MaterialTheme.typography.labelSmall) }
                            Button({ viewModel.evolve(option) }, enabled = option.eligible && !state.saving) { Text("Evolve") }
                        } }
                    } }
                    state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                }
            }
        }
    }
}

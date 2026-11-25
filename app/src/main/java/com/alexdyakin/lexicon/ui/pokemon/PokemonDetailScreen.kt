package com.alexdyakin.lexicon.ui.pokemon

import com.alexdyakin.lexicon.ui.theme.Status
import com.alexdyakin.lexicon.ui.theme.Spacing
import com.alexdyakin.lexicon.ui.components.ListRow
import com.alexdyakin.lexicon.ui.components.LexTone
import com.alexdyakin.lexicon.ui.components.LexButton
import com.alexdyakin.lexicon.ui.components.ErrorBox
import com.alexdyakin.lexicon.ui.components.ActionRow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
        val collection = safeApiCall { api.collection() }
        val pokemon = collection.successOrNull?.firstOrNull { it.id == id }
        val evolutions = pokemon?.let { safeApiCall { api.evolutionOptions(it.id) }.successOrNull }.orEmpty()
        _state.value = PokemonDetailUiState(
            false,
            pokemon = pokemon,
            // The button always started at "Favourite", even for a starred Pokémon.
            favourite = pokemon?.favourite ?: false,
            evolutions = evolutions,
            message = when {
                pokemon != null -> null
                collection is ApiResult.Success -> "That Pokémon was not found."
                else -> "Couldn't load this Pokémon. Check your connection and try again."
            },
        )
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
    ScreenScaffold("Pokémon details", onBack, gutter = Spacing.gutter) { padding ->
        when {
            state.loading -> LoadingBox(padding)
            state.pokemon == null -> ErrorBox(state.message ?: "Couldn't load this Pokémon.", { viewModel.load(id) }, padding)
            else -> PokemonDetailBody(
                pokemon = state.pokemon!!,
                state = state,
                nickname = nickname,
                onNickname = { nickname = it },
                viewModel = viewModel,
                padding = padding,
            )
        }
    }
}

/**
 * Scrolls, and every group of actions wraps. It was a fixed column with a row of three
 * filled buttons (Save name, Favourite, Set buddy) that ran off a 360dp screen, and once
 * the evolution card was added the bottom of the screen could not be reached at all.
 */
@Composable
private fun PokemonDetailBody(
    pokemon: CaughtPokemon,
    state: PokemonDetailUiState,
    nickname: String,
    onNickname: (String) -> Unit,
    viewModel: PokemonDetailViewModel,
    padding: PaddingValues,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AsyncImage(ApiUrls.spriteUrl(pokemon.spriteKey), pokemon.displayName, Modifier.size(140.dp), contentScale = ContentScale.Fit)
        Text(pokemon.displayName, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
        Text(
            listOf("Lv ${pokemon.pokemonLevel}", pokemon.type1, pokemon.type2).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.message?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                val ratio = if (pokemon.hp > 0) pokemon.currentHp.toFloat() / pokemon.hp else 0f
                Text(if (pokemon.fainted) "Fainted" else "HP ${pokemon.currentHp} / ${pokemon.hp}", style = MaterialTheme.typography.titleSmall)
                LinearProgressIndicator(
                    progress = { ratio.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = when {
                        ratio > 0.5f -> Status.ok
                        ratio > 0.2f -> Status.warn
                        else -> Status.danger
                    },
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Text(
                    "Attack ${pokemon.attack} · Defense ${pokemon.defense} · Speed ${pokemon.speed}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ActionRow {
                    LexButton(
                        "Use potion",
                        onClick = { viewModel.heal("POTION") },
                        enabled = !state.saving && !pokemon.fainted && pokemon.currentHp < pokemon.hp,
                    )
                    LexButton("Use revive", onClick = { viewModel.heal("REVIVE") }, enabled = !state.saving && pokemon.fainted)
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    nickname,
                    onNickname,
                    label = { Text("Nickname") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ActionRow {
                    LexButton(
                        "Save name",
                        onClick = { viewModel.nickname(nickname.trim()) },
                        enabled = !state.saving && nickname.trim() != pokemon.nickname,
                    )
                    LexButton(
                        if (state.favourite) "Favourite" else "Add to favourites",
                        onClick = viewModel::favourite,
                        icon = if (state.favourite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        enabled = !state.saving,
                    )
                    LexButton("Make buddy", onClick = viewModel::buddy, enabled = !state.saving)
                }
            }
        }

        if (state.evolutions.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text("Evolution", style = MaterialTheme.typography.titleMedium)
                    state.evolutions.forEach { option ->
                        ListRow(
                            action = {
                                LexButton(
                                    "Evolve",
                                    onClick = { viewModel.evolve(option) },
                                    tone = LexTone.Primary,
                                    enabled = option.eligible && !state.saving,
                                )
                            },
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(option.evolvesToName, style = MaterialTheme.typography.bodyLarge)
                                if (option.reason.isNotBlank()) {
                                    Text(option.reason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

package com.alexdyakin.lexicon.ui.pokemon

import com.alexdyakin.lexicon.ui.theme.LexPalette
import androidx.compose.ui.graphics.luminance
import com.alexdyakin.lexicon.ui.theme.PokemonTypeColors
import com.alexdyakin.lexicon.ui.theme.Status
import com.alexdyakin.lexicon.ui.components.ActionRow
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import kotlin.math.sin
import kotlin.math.PI
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.LinearEasing
import com.alexdyakin.lexicon.ui.components.ErrorBox
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import com.alexdyakin.lexicon.ui.components.LaunchedWhileResumed
import com.alexdyakin.lexicon.data.safeApiCall
import com.alexdyakin.lexicon.data.ApiResult
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.alexdyakin.lexicon.R
import com.alexdyakin.lexicon.data.CaughtPokemon
import com.alexdyakin.lexicon.data.ApiUrls
import com.alexdyakin.lexicon.data.api.PokemonApi
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import com.alexdyakin.lexicon.ui.components.EmptyBox
import com.alexdyakin.lexicon.ui.components.LoadingBox
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import com.alexdyakin.lexicon.ui.components.LexButton
import com.alexdyakin.lexicon.ui.components.LexTone
import com.alexdyakin.lexicon.ui.theme.Spacing
import com.alexdyakin.lexicon.ui.components.ScreenScaffold
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PokemonUiState(
    val loading: Boolean = true,
    val collection: List<CaughtPokemon> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class PokemonViewModel @Inject constructor(
    private val pokemonApi: PokemonApi,
) : ViewModel() {
    private val _state = MutableStateFlow(PokemonUiState())
    val state: StateFlow<PokemonUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            _state.value = when (val result = safeApiCall { pokemonApi.collection() }) {
                is ApiResult.Success -> PokemonUiState(loading = false, collection = result.data)
                // A failed refresh keeps what is already on screen; the error shows only when
                // there is nothing else to show.
                is ApiResult.Failure -> _state.value.copy(loading = false, error = result.message)
                ApiResult.Unauthorized -> _state.value.copy(
                    loading = false,
                    error = "Your session has expired. Sign in again to see your Pokémon.",
                )
            }
        }
    }
}

@Composable
fun PokemonScreen(
    onBack: () -> Unit,
    onOpenMap: () -> Unit,
    onOpenPokedex: () -> Unit,
    onOpenShop: () -> Unit,
    onOpenEggs: () -> Unit,
    onOpenPokemon: (Long) -> Unit,
    viewModel: PokemonViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    // Back from the map or an egg hatching: a catch made there showed only after leaving
    // and re-entering this screen. The first resume is skipped; init already loaded.
    var resumedBefore by remember { mutableStateOf(false) }
    LaunchedWhileResumed(Unit) {
        if (resumedBefore) viewModel.refresh() else resumedBefore = true
    }

    // One clock for the whole grid. Each card used to run its own infinite transition, so a
    // collection of 60 kept 60 animations recomposing 60 cards every frame.
    val bobClock by rememberInfiniteTransition(label = "bob").animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)),
        label = "bobClock",
    )

    // Dex, Eggs and Shop were three text buttons in the top bar, which crushed the title; they
    // are places to go in Pokémon, so they sit with the map button instead.
    ScreenScaffold(title = "Pokémon", onBack = onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Outside the `when` on purpose. The map is where Pokemon come from, so it
            // has to be reachable in every state — including the empty one a new player
            // lands on, which used to show "No Pokemon caught yet" and no way at all to
            // go and catch one.
            LexButton(
                "Open the map",
                onClick = onOpenMap,
                tone = LexTone.Primary,
                icon = Icons.Filled.Map,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.gutter)
                    .padding(top = Spacing.md),
            )
            ActionRow(Modifier.padding(horizontal = Spacing.gutter).padding(top = Spacing.sm)) {
                LexButton("Pokédex", onClick = onOpenPokedex)
                LexButton("Eggs", onClick = onOpenEggs)
                LexButton("Shop", onClick = onOpenShop)
            }
            when {
                state.loading && state.collection.isEmpty() -> LoadingBox(PaddingValues(0.dp))
                state.error != null && state.collection.isEmpty() ->
                    ErrorBox(state.error!!, viewModel::refresh, PaddingValues(0.dp))
                state.collection.isEmpty() ->
                    EmptyBox(PaddingValues(0.dp), "No Pokémon caught yet.")
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(state.collection, key = { _, p -> p.id }) { index, pokemon ->
                        PokemonCard(pokemon, index, bobClock = { bobClock }) { onOpenPokemon(pokemon.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PokemonCard(pokemon: CaughtPokemon, index: Int, bobClock: () -> Float, onClick: () -> Unit) {
    // Sprites bob out of step with each other, so a full grid feels alive rather than like a
    // spreadsheet of images. The shared clock is read in the draw phase (graphicsLayer), so a
    // tick moves the sprite without recomposing the card.
    val phase = index * 1.3f

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
        ),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(
            Modifier.padding(12.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(88.dp)
                        .alpha(0.3f)
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    PokemonTypeColors.of(pokemon.type1).copy(alpha = 0.8f),
                                    Color.Transparent,
                                )
                            ),
                            shape = RoundedCornerShape(44.dp),
                        )
                )
                AsyncImage(
                    model = ApiUrls.spriteUrl(pokemon.spriteKey),
                    contentDescription = pokemon.displayName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(76.dp)
                        .graphicsLayer { translationY = sin(bobClock() + phase) * 4.dp.toPx() }
                        .alpha(if (pokemon.fainted) 0.45f else 1f),
                )
            }

            Text(
                pokemon.displayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Lv ${pokemon.pokemonLevel}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TypeChip(pokemon.type1)
                if (pokemon.type2.isNotBlank()) TypeChip(pokemon.type2)
            }

            if (pokemon.hp > 0) {
                Spacer(Modifier.height(8.dp))
                val ratio = (pokemon.currentHp.toFloat() / pokemon.hp).coerceIn(0f, 1f)
                val animatedRatio by animateFloatAsState(
                    targetValue = ratio,
                    animationSpec = tween(900, easing = FastOutSlowInEasing),
                    label = "hp",
                )
                LinearProgressIndicator(
                    progress = { animatedRatio },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = when {
                        ratio > 0.5f -> Status.ok
                        ratio > 0.2f -> Status.warn
                        else -> Status.danger
                    },
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TypeChip(type: String) {
    if (type.isBlank()) return
    val fill = PokemonTypeColors.of(type)
    Surface(
        color = fill,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            type.lowercase().replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.labelSmall,
            // White on Electric, Ice or Steel was close to unreadable; light types get dark text.
            color = if (fill.luminance() > 0.45f) LexPalette.Lowest else Color.White,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}


package com.alexdyakin.lexicon.ui.pokemon

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
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
import com.alexdyakin.lexicon.ui.components.ScreenScaffold
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException

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
            try {
                val collection = pokemonApi.collection()
                _state.value = PokemonUiState(loading = false, collection = collection)
            } catch (e: HttpException) {
                _state.value = PokemonUiState(
                    loading = false,
                    error = if (e.code() == 401)
                        "Pokémon server rejected the login token.\n\nIt needs the bearer-token update deployed before this works."
                    else "Could not load your collection (HTTP ${e.code()}).",
                )
            } catch (e: Exception) {
                _state.value = PokemonUiState(
                    loading = false,
                    error = "Could not reach the Pokémon server. ${e.message ?: ""}".trim(),
                )
            }
        }
    }
}

@Composable
fun PokemonScreen(
    onBack: () -> Unit,
    onOpenPokedex: () -> Unit,
    onOpenShop: () -> Unit,
    onOpenEggs: () -> Unit,
    onOpenPokemon: (Long) -> Unit,
    viewModel: PokemonViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    ScreenScaffold(title = "Pokémon", onBack = onBack, background = R.drawable.bg_dashboard, actions = { TextButton(onClick = onOpenPokedex) { Text("Dex") }; TextButton(onClick = onOpenEggs) { Text("Eggs") }; TextButton(onClick = onOpenShop) { Text("Shop") } }) { padding ->
        when {
            state.loading -> LoadingBox(padding)
            state.error != null -> EmptyBox(padding, state.error!!)
            state.collection.isEmpty() -> EmptyBox(padding, "No Pokémon caught yet.")
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(state.collection, key = { _, p -> p.id }) { index, pokemon ->
                    PokemonCard(pokemon, index) { onOpenPokemon(pokemon.id) }
                }
            }
        }
    }
}

@Composable
private fun PokemonCard(pokemon: CaughtPokemon, index: Int, onClick: () -> Unit) {
    // Sprites bob on staggered cycles, so a full grid feels alive rather than
    // like a spreadsheet of images
    val transition = rememberInfiniteTransition(label = "bob$index")
    val bob by transition.animateFloat(
        initialValue = -4f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500 + (index % 5) * 220, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bob",
    )

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
                                    typeColor(pokemon.type1).copy(alpha = 0.8f),
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
                        .offset(y = bob.dp)
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
                        ratio > 0.5f -> Color(0xFF6B8F5E)
                        ratio > 0.2f -> Color(0xFFC8A24C)
                        else -> Color(0xFFB4552F)
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
    Surface(
        color = typeColor(type).copy(alpha = 0.85f),
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            type.lowercase().replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** Standard type colours, so the grid reads at a glance. */
private fun typeColor(type: String): Color = when (type.lowercase()) {
    "fire" -> Color(0xFFEE8130)
    "water" -> Color(0xFF6390F0)
    "grass" -> Color(0xFF7AC74C)
    "electric" -> Color(0xFFF7D02C)
    "psychic" -> Color(0xFFF95587)
    "ice" -> Color(0xFF96D9D6)
    "dragon" -> Color(0xFF6F35FC)
    "dark" -> Color(0xFF705746)
    "fairy" -> Color(0xFFD685AD)
    "fighting" -> Color(0xFFC22E28)
    "flying" -> Color(0xFFA98FF3)
    "poison" -> Color(0xFFA33EA1)
    "ground" -> Color(0xFFE2BF65)
    "rock" -> Color(0xFFB6A136)
    "bug" -> Color(0xFFA6B91A)
    "ghost" -> Color(0xFF735797)
    "steel" -> Color(0xFFB7B7CE)
    else -> Color(0xFF9FA19F)
}

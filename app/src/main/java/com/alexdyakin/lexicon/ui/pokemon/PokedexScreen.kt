package com.alexdyakin.lexicon.ui.pokemon

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.alexdyakin.lexicon.R
import com.alexdyakin.lexicon.data.ApiUrls
import com.alexdyakin.lexicon.data.PokemonSpecies
import com.alexdyakin.lexicon.data.api.PokemonApi
import com.alexdyakin.lexicon.ui.components.EmptyBox
import com.alexdyakin.lexicon.ui.components.LoadingBox
import com.alexdyakin.lexicon.ui.components.ScreenScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PokedexUiState(val loading: Boolean = true, val species: List<PokemonSpecies> = emptyList(), val caught: Set<Int> = emptySet(), val error: String? = null)

@HiltViewModel
class PokedexViewModel @Inject constructor(private val api: PokemonApi) : ViewModel() {
    private val _state = MutableStateFlow(PokedexUiState())
    val state = _state.asStateFlow()
    init { viewModelScope.launch { runCatching { api.species() to api.caughtSpecies().toSet() }.onSuccess { (species, caught) -> _state.value = PokedexUiState(false, species, caught) }.onFailure { _state.value = PokedexUiState(false, error = "Could not load the Pokédex.") } } }
}

@Composable
fun PokedexScreen(onBack: () -> Unit, viewModel: PokedexViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("ALL") }
    val visible = state.species.filter { species ->
        (filter == "ALL" || (filter == "CAUGHT" && species.id in state.caught) || (filter == "UNCUGHT" && species.id !in state.caught)) && species.name.contains(query, true)
    }
    ScreenScaffold("Pokédex", onBack, R.drawable.bg_dashboard) { padding ->
        when {
            state.loading -> LoadingBox(padding)
            state.error != null -> EmptyBox(padding, state.error!!)
            else -> Column(Modifier.fillMaxSize().padding(padding).padding(12.dp)) {
                Text("${state.caught.size} / ${state.species.size} species caught", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(query, { query = it }, label = { Text("Search species") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                androidx.compose.foundation.layout.Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("ALL", "CAUGHT", "UNCUGHT").forEach { item ->
                        androidx.compose.material3.FilterChip(selected = filter == item, onClick = { filter = item }, label = { Text(item.lowercase().replaceFirstChar { it.uppercase() }) })
                    }
                }
                LazyVerticalGrid(GridCells.Adaptive(110.dp), contentPadding = PaddingValues(4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(visible, key = { it.id }) { species -> SpeciesCard(species, species.id in state.caught) }
                }
            }
        }
    }
}

@Composable private fun SpeciesCard(species: PokemonSpecies, caught: Boolean) = Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        AsyncImage(model = ApiUrls.spriteUrl(species.spriteKey), contentDescription = species.name, contentScale = ContentScale.Fit, colorFilter = if (caught) null else ColorFilter.tint(MaterialTheme.colorScheme.onSurface), modifier = Modifier.size(66.dp).alpha(if (caught) 1f else .35f))
        Text("#${species.id}", style = MaterialTheme.typography.labelSmall)
        Text(if (caught) species.name else "???", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
    }
}

package com.alexdyakin.lexicon.ui.pokemon

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexdyakin.lexicon.R
import com.alexdyakin.lexicon.data.ApiResult
import com.alexdyakin.lexicon.data.IncubateEggRequest
import com.alexdyakin.lexicon.data.PlayerEgg
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

data class EggsUiState(val loading: Boolean = true, val saving: Boolean = false, val eggs: List<PlayerEgg> = emptyList(), val message: String? = null)

@HiltViewModel
class EggsViewModel @Inject constructor(private val api: PokemonApi) : ViewModel() {
    private val _state = MutableStateFlow(EggsUiState())
    val state = _state.asStateFlow()
    init { refresh() }
    fun refresh() = viewModelScope.launch { val result = safeApiCall { api.eggs() }; _state.value = EggsUiState(false, eggs = result.successOrNull.orEmpty(), message = (result as? ApiResult.Failure)?.message) }
    fun incubate(egg: PlayerEgg) = viewModelScope.launch {
        _state.value = _state.value.copy(saving = true, message = null)
        when (val result = safeApiCall { api.incubate(IncubateEggRequest(egg.id)) }) {
            is ApiResult.Success -> _state.value = _state.value.copy(saving = false, eggs = _state.value.eggs.map { if (it.id == egg.id) it.copy(incubating = true) else it }, message = "Egg is now incubating.")
            is ApiResult.Failure -> _state.value = _state.value.copy(saving = false, message = result.message)
            ApiResult.Unauthorized -> _state.value = _state.value.copy(saving = false, message = "Sign in again to incubate eggs.")
        }
    }
}

@Composable
fun EggsScreen(onBack: () -> Unit, viewModel: EggsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    ScreenScaffold("Eggs", onBack, R.drawable.bg_dashboard) { padding ->
        when {
            state.loading -> LoadingBox(padding)
            state.eggs.isEmpty() -> EmptyBox(padding, state.message ?: "No eggs yet. Explore the map and spin PokéStops.")
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Text("${state.eggs.count { it.incubating }} incubating · ${state.eggs.size} total", style = MaterialTheme.typography.titleMedium); state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) } }
                items(state.eggs, key = { it.id }) { egg -> EggCard(egg, state.saving, viewModel::incubate) }
            }
        }
    }
}

@Composable private fun EggCard(egg: PlayerEgg, saving: Boolean, incubate: (PlayerEgg) -> Unit) {
    val progress = if (egg.distanceKm == 0.0) 0f else (egg.progressKm / egg.distanceKm).toFloat().coerceIn(0f, 1f)
    val color = when (egg.distanceKm.toInt()) { 2 -> Color(0xFF74C69D); 5 -> Color(0xFFF4A261); else -> Color(0xFFC77DFF) }
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${egg.distanceKm.toInt()} km Egg", style = MaterialTheme.typography.titleMedium, color = color)
        Text(if (egg.incubating) "Incubating" else "Stored egg")
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = color)
        Text("${"%.2f".format(egg.progressKm)} / ${egg.distanceKm} km")
        if (!egg.incubating) Button({ incubate(egg) }, enabled = !saving) { Text("Use incubator") }
    } }
}

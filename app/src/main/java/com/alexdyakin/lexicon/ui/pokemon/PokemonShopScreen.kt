package com.alexdyakin.lexicon.ui.pokemon

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexdyakin.lexicon.R
import com.alexdyakin.lexicon.data.ApiResult
import com.alexdyakin.lexicon.data.PlayerItem
import com.alexdyakin.lexicon.data.ShopBuyRequest
import com.alexdyakin.lexicon.data.ShopItem
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

data class ShopUiState(val loading: Boolean = true, val saving: Boolean = false, val items: List<PlayerItem> = emptyList(), val catalog: List<ShopItem> = emptyList(), val coins: Int = 0, val message: String? = null)

@HiltViewModel
class PokemonShopViewModel @Inject constructor(private val api: PokemonApi) : ViewModel() {
    private val _state = MutableStateFlow(ShopUiState())
    val state = _state.asStateFlow()
    init { refresh() }
    fun refresh() = viewModelScope.launch {
        val items = safeApiCall { api.items() }.successOrNull.orEmpty()
        val catalog = safeApiCall { api.shopCatalog() }.successOrNull.orEmpty()
        val coins = safeApiCall { api.playerStats() }.successOrNull?.coins ?: 0
        _state.value = ShopUiState(false, items = items, catalog = catalog, coins = coins)
    }
    fun buy(item: ShopItem) = viewModelScope.launch {
        _state.value = _state.value.copy(saving = true, message = null)
        when (val result = safeApiCall { api.buy(ShopBuyRequest(item.itemType)) }) {
            is ApiResult.Success -> {
                val newItems = _state.value.items.toMutableList()
                val index = newItems.indexOfFirst { it.itemType == item.itemType }
                if (index >= 0) newItems[index] = newItems[index].copy(quantity = newItems[index].quantity + 1) else newItems += PlayerItem(item.itemType, 1)
                _state.value = _state.value.copy(saving = false, items = newItems, coins = result.data.coinsRemaining, message = result.data.message)
            }
            is ApiResult.Failure -> _state.value = _state.value.copy(saving = false, message = result.message)
            ApiResult.Unauthorized -> _state.value = _state.value.copy(saving = false, message = "Sign in again to make purchases.")
        }
    }
}

@Composable
fun PokemonShopScreen(onBack: () -> Unit, viewModel: PokemonShopViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    ScreenScaffold("PokéMart", onBack, R.drawable.bg_dashboard) { padding ->
        when {
            state.loading -> LoadingBox(padding)
            state.catalog.isEmpty() -> EmptyBox(padding, "The PokéMart is unavailable.")
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Text("${state.coins} coins", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary); state.message?.let { Text(it) }; Text("Bag", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp)); Text(state.items.joinToString { "${it.itemType.replace('_', ' ')} ×${it.quantity}" }.ifBlank { "Your bag is empty." }) }
                item { Text("Shop", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
                items(state.catalog, key = { it.itemType }) { item ->
                    Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(item.label, style = MaterialTheme.typography.titleMedium); Text("${item.price} coins") }; Button(onClick = { viewModel.buy(item) }, enabled = !state.saving && state.coins >= item.price) { Text("Buy") } } }
                }
            }
        }
    }
}

package com.alexdyakin.lexicon.ui.alchemy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexdyakin.lexicon.R
import com.alexdyakin.lexicon.data.KnowledgeEntry
import com.alexdyakin.lexicon.data.TokenStore
import com.alexdyakin.lexicon.data.api.AlchemyApi
import com.alexdyakin.lexicon.ui.components.LoadingBox
import com.alexdyakin.lexicon.ui.components.OnScrim
import com.alexdyakin.lexicon.ui.components.OnScrimDim
import com.alexdyakin.lexicon.ui.components.ScreenScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class KnowledgeUiState(val loading: Boolean = true, val entries: List<KnowledgeEntry> = emptyList(), val error: String? = null)
@HiltViewModel class KnowledgeBookViewModel @Inject constructor(private val api: AlchemyApi, private val tokenStore: TokenStore) : ViewModel() {
    private val _state = MutableStateFlow(KnowledgeUiState()); val state: StateFlow<KnowledgeUiState> = _state.asStateFlow()
    init { viewModelScope.launch { try { _state.value = KnowledgeUiState(false, api.knowledge(tokenStore.userId)) } catch (e: Exception) { _state.value = KnowledgeUiState(false, error = e.message ?: "Could not load knowledge.") } } }
}
@Composable fun KnowledgeBookScreen(onBack: () -> Unit, viewModel: KnowledgeBookViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    ScreenScaffold("Knowledge book", onBack, R.drawable.bg_dashboard) { padding -> when {
        state.loading -> LoadingBox(padding)
        state.error != null -> Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.Center) {
            Text(state.error!!, color = OnScrim, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 20.dp))
        }
        state.entries.isEmpty() -> Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.Center) {
            Text("Discover effects by eating ingredients and brewing potions.", color = OnScrimDim, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 20.dp))
        }
        else -> LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.entries, key = { it.ingredientId }) { entry ->
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            entry.ingredientName,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFF1E4B8F),
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (entry.effects.isEmpty()) {
                            Text(
                                "Known effects: unknown",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        } else {
                            entry.effects.forEach { effect ->
                                Text(
                                    "• ${effect.title}: ${effect.description}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    } }
}
package com.alexdyakin.lexicon.ui.lexicon

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.alexdyakin.lexicon.data.StorageInfo
import com.alexdyakin.lexicon.data.StorageVolume
import com.alexdyakin.lexicon.data.api.MediaApi
import com.alexdyakin.lexicon.data.safeApiCall
import com.alexdyakin.lexicon.ui.components.EmptyBox
import com.alexdyakin.lexicon.ui.components.LoadingBox
import com.alexdyakin.lexicon.ui.components.ScreenScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StorageUiState(val loading: Boolean = true, val info: StorageInfo? = null, val error: String? = null)
@HiltViewModel class StorageDashboardViewModel @Inject constructor(private val api: MediaApi) : ViewModel() {
    private val _state = MutableStateFlow(StorageUiState()); val state = _state.asStateFlow()
    init { viewModelScope.launch { when (val result = safeApiCall { api.storageInfo() }) { is ApiResult.Success -> _state.value = StorageUiState(false, result.data); is ApiResult.Failure -> _state.value = StorageUiState(false, error = result.message); ApiResult.Unauthorized -> _state.value = StorageUiState(false, error = "Sign in again to view storage.") } } }
}
@Composable fun StorageDashboardScreen(onBack: () -> Unit, viewModel: StorageDashboardViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    ScreenScaffold("Media storage", onBack, R.drawable.bg_dashboard) { padding -> when { state.loading -> LoadingBox(padding); state.info == null -> EmptyBox(padding, state.error ?: "Storage information is unavailable."); else -> LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { item { StorageSummary(state.info!!) }; items(state.info!!.volumes, key = { it.label }) { StorageCard(it) } } } }
}
@Composable private fun StorageSummary(info: StorageInfo) { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { val used = if (info.totalBytes == 0L) 0f else info.usedBytes.toFloat() / info.totalBytes; Text("${formatBytes(info.usedBytes)} used of ${formatBytes(info.totalBytes)}", style = MaterialTheme.typography.titleMedium); LinearProgressIndicator(progress = { used }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp), color = storageColor(used)); Text("${formatBytes(info.freeBytes)} available", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) } } }
@Composable private fun StorageCard(volume: StorageVolume) { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { val used = if (volume.totalBytes == 0L) 0f else volume.usedBytes.toFloat() / volume.totalBytes; Text(volume.label, style = MaterialTheme.typography.titleMedium); Text("${formatBytes(volume.usedBytes)} / ${formatBytes(volume.totalBytes)}"); LinearProgressIndicator(progress = { used }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = storageColor(used)); Text("${formatBytes(volume.freeBytes)} free", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 5.dp)) } } }
private fun storageColor(used: Float) = when { used >= .9f -> Color(0xFFB4552F); used >= .7f -> Color(0xFFC8A24C); else -> Color(0xFF6B8F5E) }
private fun formatBytes(bytes: Long): String = when { bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0); bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0); else -> "$bytes B" }

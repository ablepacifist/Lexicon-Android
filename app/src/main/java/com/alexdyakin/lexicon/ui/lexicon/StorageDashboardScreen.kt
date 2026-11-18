package com.alexdyakin.lexicon.ui.lexicon

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexdyakin.lexicon.data.ApiResult
import com.alexdyakin.lexicon.data.StorageInfo
import com.alexdyakin.lexicon.data.StorageVolume
import com.alexdyakin.lexicon.data.api.MediaApi
import com.alexdyakin.lexicon.data.safeApiCall
import com.alexdyakin.lexicon.ui.components.EmptyBox
import com.alexdyakin.lexicon.ui.components.LoadingBox
import com.alexdyakin.lexicon.ui.components.ScreenScaffold
import com.alexdyakin.lexicon.ui.theme.Elevation
import com.alexdyakin.lexicon.ui.theme.Radii
import com.alexdyakin.lexicon.ui.theme.Spacing
import com.alexdyakin.lexicon.ui.theme.Status
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StorageUiState(
    val loading: Boolean = true,
    val info: StorageInfo? = null,
    val error: String? = null,
)

@HiltViewModel
class StorageDashboardViewModel @Inject constructor(
    private val api: MediaApi,
) : ViewModel() {
    private val _state = MutableStateFlow(StorageUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = when (val result = safeApiCall { api.storageInfo() }) {
                is ApiResult.Success -> StorageUiState(loading = false, info = result.data)
                is ApiResult.Failure -> StorageUiState(loading = false, error = result.message)
                ApiResult.Unauthorized ->
                    StorageUiState(loading = false, error = "Sign in again to view storage.")
            }
        }
    }
}

@Composable
fun StorageDashboardScreen(
    onBack: () -> Unit,
    viewModel: StorageDashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    ScreenScaffold("Media storage", onBack, gutter = Spacing.gutter) { padding ->
        val info = state.info
        when {
            state.loading -> LoadingBox(padding)
            info == null -> EmptyBox(padding, state.error ?: "Storage information is unavailable.")
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(vertical = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                item { StorageSummary(info) }
                items(info.volumes, key = { it.label }) { StorageCard(it) }
            }
        }
    }
}

@Composable
private fun StorageSummary(info: StorageInfo) {
    val used = if (info.totalBytes == 0L) 0f else info.usedBytes.toFloat() / info.totalBytes
    StorageMeter(
        title = "${formatBytes(info.usedBytes)} used of ${formatBytes(info.totalBytes)}",
        detail = "${formatBytes(info.freeBytes)} available",
        fraction = used,
    )
}

@Composable
private fun StorageCard(volume: StorageVolume) {
    val used = if (volume.totalBytes == 0L) 0f else volume.usedBytes.toFloat() / volume.totalBytes
    StorageMeter(
        title = volume.label,
        detail = "${formatBytes(volume.usedBytes)} / ${formatBytes(volume.totalBytes)} · " +
            "${formatBytes(volume.freeBytes)} free",
        fraction = used,
    )
}

/**
 * One card shape for both the whole-library summary and each volume.
 *
 * They were two near-identical card bodies that had drifted apart: 10dp of top padding on one and
 * 8dp on the other, 6dp and 5dp below, and a different type role for the same line of text.
 */
@Composable
private fun StorageMeter(title: String, detail: String, fraction: Float) {
    Card(
        shape = Radii.card,
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = Elevation.card),
    ) {
        Column(
            Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${(fraction * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = storageColor(fraction),
                    maxLines = 1,
                )
            }
            LinearProgressIndicator(
                progress = { fraction.coerceIn(0f, 1f) },
                color = storageColor(fraction),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().height(Spacing.sm),
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Same thresholds as the web dashboard, now in the shared palette. This screen used to mix its own
 * terracotta/ochre/sage triple — a fourth reinvented traffic light, and the only one still in the
 * retired medieval palette.
 */
private fun storageColor(used: Float): Color = when {
    used >= 0.90f -> Status.danger
    used >= 0.70f -> Status.warn
    else -> Status.ok
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    else -> "$bytes B"
}

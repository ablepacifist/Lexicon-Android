package com.alexdyakin.lexicon.ui.lexicon

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexdyakin.lexicon.R
import com.alexdyakin.lexicon.data.StorageInfo
import com.alexdyakin.lexicon.data.api.MediaApi
import com.alexdyakin.lexicon.data.safeApiCall
import com.alexdyakin.lexicon.ui.components.LoadingBox
import com.alexdyakin.lexicon.ui.components.OnScrim
import com.alexdyakin.lexicon.ui.components.ScreenScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LexiconUiState(
    val loading: Boolean = true,
    val storage: StorageInfo? = null,
)

@HiltViewModel
class LexiconViewModel @Inject constructor(
    private val mediaApi: MediaApi,
) : ViewModel() {
    private val _state = MutableStateFlow(LexiconUiState())
    val state: StateFlow<LexiconUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            // The web dashboard swallows a failed storage-info and simply hides the bars.
            val storage = safeApiCall { mediaApi.storageInfo() }.successOrNull
            _state.value = LexiconUiState(loading = false, storage = storage)
        }
    }
}

/**
 * Section groupings and card copy are taken verbatim from `Lexicon/src/pages/LexiconDashboard.js`
 * (`SECTIONS`). Do not add cards here that the web dashboard does not have — Events lives at its
 * own top-level destination, exactly as `/events` does in the web Navbar.
 */
private data class DashCard(
    val route: String,
    val icon: String,
    val title: String,
    val desc: String,
)

private data class DashSection(
    val badge: String,
    val title: String,
    val cards: List<DashCard>,
)

@Composable
fun LexiconScreen(
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onOpenProfile: () -> Unit,
    displayName: String,
    viewModel: LexiconViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    val sections = remember {
        listOf(
            DashSection(
                badge = "Watch & Listen",
                title = "Your Media",
                cards = listOf(
                    DashCard("video-player", "🎬", "Video Player", "Browse, search, and watch your video library."),
                    DashCard("audio-player", "🎵", "Audio Player", "Listen to your music collection, track by track."),
                    DashCard("audiobooks", "📚", "Audiobooks", "Long-form audio — books, lectures, and more."),
                ),
            ),
            DashSection(
                badge = "Library",
                title = "Manage Your Files",
                cards = listOf(
                    DashCard("media-upload", "⬆️", "Upload & Download", "Add files to your library or retrieve them anytime."),
                    DashCard("playlists", "📋", "Playlists", "Build and organise playlists across all your media."),
                ),
            ),
            DashSection(
                badge = "Go Live",
                title = "Broadcast",
                cards = listOf(
                    DashCard("video-stream", "📡", "Video Stream", "Go live with a full video broadcast."),
                    DashCard("music-stream", "🎙️", "Music Stream", "Stream music live to your audience."),
                    DashCard("queue-manager?channel=video", "🗂️", "Queue Manager", "Manage and control the live stream playback queue in real time."),
                ),
            ),
        )
    }

    ScreenScaffold(title = "Lexicon", onBack = onBack, background = R.drawable.bg_dashboard) { padding ->
        if (state.loading) {
            LoadingBox(padding)
            return@ScreenScaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { HeroStrip(displayName = displayName, onOpenProfile = onOpenProfile, onBack = onBack) }

            state.storage?.volumes?.takeIf { it.isNotEmpty() }?.let { volumes ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            volumes.forEach { volume -> StorageBar(volume.label, volume.usedBytes, volume.totalBytes) }
                        }
                    }
                }
            }

            sections.forEach { section ->
                item { SectionBadge(section.badge, section.title) }
                section.cards.forEach { card ->
                    item { DashboardCard(card) { onOpen(card.route) } }
                }
            }
        }
    }
}

@Composable
private fun HeroStrip(displayName: String, onOpenProfile: () -> Unit, onBack: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Welcome back, $displayName",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Lexicon Media Center",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenProfile) { Text("👤 Profile") }
                OutlinedButton(onClick = onBack) { Text("← Apps") }
            }
        }
    }
}

/** Mirrors `.lex-section-header`: coloured badge, title, divider. */
@Composable
private fun SectionBadge(badge: String, title: String) {
    Column(Modifier.padding(top = 10.dp, bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
            shape = RoundedCornerShape(50),
        ) {
            Text(
                badge.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = OnScrim,
        )
        HorizontalDivider(
            Modifier.fillMaxWidth(0.35f),
            thickness = 2.dp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun DashboardCard(card: DashCard, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(card.icon, style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    card.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    card.desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("→", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Thresholds match `LexiconDashboard.js`: >90% red, >70% amber, otherwise green. */
@Composable
private fun StorageBar(label: String, usedBytes: Long, totalBytes: Long) {
    val fraction = if (totalBytes > 0) (usedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
    val pct = fraction * 100f
    val color = when {
        pct > 90f -> Color(0xFFE74C3C)
        pct > 70f -> Color(0xFFF1C40F)
        else -> Color(0xFF2ECC71)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${toGb(usedBytes)} / ${toGb(totalBytes)} GB",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { fraction },
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().height(8.dp),
        )
    }
}

private fun toGb(bytes: Long): String = String.format("%.1f", bytes / 1024.0 / 1024.0 / 1024.0)

package com.alexdyakin.lexicon.ui.lexicon

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexdyakin.lexicon.data.StorageInfo
import com.alexdyakin.lexicon.data.api.MediaApi
import com.alexdyakin.lexicon.data.safeApiCall
import com.alexdyakin.lexicon.ui.components.LexIconButton
import com.alexdyakin.lexicon.ui.components.LoadingBox
import com.alexdyakin.lexicon.ui.components.ScreenScaffold
import com.alexdyakin.lexicon.ui.theme.Elevation
import com.alexdyakin.lexicon.ui.theme.Radii
import com.alexdyakin.lexicon.ui.theme.Sizes
import com.alexdyakin.lexicon.ui.theme.Spacing
import com.alexdyakin.lexicon.ui.theme.Status
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
 *
 * The web version's emoji are real icons here. An emoji takes no tint, does not scale with the
 * type ramp, carries no content description, and renders at a different width on every device —
 * which makes it impossible to reason about whether the row holding it fits.
 */
private data class DashCard(
    val route: String,
    val icon: ImageVector,
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
                    DashCard("video-player", Icons.Filled.Movie, "Video Player", "Browse, search, and watch your video library."),
                    DashCard("audio-player", Icons.Filled.MusicNote, "Audio Player", "Listen to your music collection, track by track."),
                    DashCard("audiobooks", Icons.AutoMirrored.Filled.MenuBook, "Audiobooks", "Long-form audio — books, lectures, and more."),
                ),
            ),
            DashSection(
                badge = "Library",
                title = "Manage Your Files",
                cards = listOf(
                    DashCard("media-upload", Icons.Filled.CloudUpload, "Upload & Download", "Add files to your library or retrieve them anytime."),
                    DashCard("playlists", Icons.AutoMirrored.Filled.PlaylistPlay, "Playlists", "Build and organise playlists across all your media."),
                ),
            ),
            DashSection(
                badge = "Go Live",
                title = "Broadcast",
                cards = listOf(
                    DashCard("video-stream", Icons.Filled.Videocam, "Video Stream", "Go live with a full video broadcast."),
                    DashCard("music-stream", Icons.Filled.Mic, "Music Stream", "Stream music live to your audience."),
                    DashCard("queue-manager?channel=video", Icons.AutoMirrored.Filled.QueueMusic, "Queue Manager", "Manage and control the live stream playback queue in real time."),
                ),
            ),
        )
    }

    ScreenScaffold(
        title = "Lexicon",
        onBack = onBack,
        gutter = Spacing.gutter,
        // Profile used to be a pill inside the hero card, sitting next to a second "Apps" back
        // control on a screen that already has the scaffold's arrow. The duplicate is gone, and
        // the one real action lives where every other screen keeps its actions.
        actions = {
            LexIconButton(
                icon = Icons.Filled.Person,
                contentDescription = "Profile",
                onClick = onOpenProfile,
            )
        },
    ) { padding ->
        if (state.loading) {
            LoadingBox(padding)
            return@ScreenScaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(vertical = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item { HeroStrip(displayName = displayName) }

            state.storage?.volumes?.takeIf { it.isNotEmpty() }?.let { volumes ->
                item {
                    // Opens the storage dashboard, which had a route but nothing leading to it.
                    Card(
                        onClick = { onOpen("storage") },
                        shape = Radii.card,
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = Elevation.card),
                    ) {
                        Column(
                            Modifier.padding(Spacing.lg),
                            verticalArrangement = Arrangement.spacedBy(Spacing.md),
                        ) {
                            volumes.forEach { volume ->
                                StorageBar(volume.label, volume.usedBytes, volume.totalBytes)
                            }
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
private fun HeroStrip(displayName: String) {
    Card(
        shape = Radii.card,
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = Elevation.card),
    ) {
        Column(
            Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(
                "Welcome back, $displayName",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Lexicon Media Center",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Mirrors `.lex-section-header`: coloured badge, title, divider. */
@Composable
private fun SectionBadge(badge: String, title: String) {
    Column(
        Modifier.padding(top = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
            shape = Radii.pill,
        ) {
            Text(
                badge.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
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
        shape = Radii.card,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = Elevation.card),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(Spacing.lg)
                .heightIn(min = Sizes.touch),
            horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                card.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(Sizes.iconLg),
            )
            // Both texts are capped, so a long description cannot grow the card around the
            // chevron or push it off the end of the row.
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(
                    card.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    card.desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(Sizes.icon),
            )
        }
    }
}

/** Thresholds match `LexiconDashboard.js`: >90% red, >70% amber, otherwise green. */
@Composable
private fun StorageBar(label: String, usedBytes: Long, totalBytes: Long) {
    val fraction = if (totalBytes > 0) {
        (usedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val color = when {
        fraction > 0.90f -> Status.danger
        fraction > 0.70f -> Status.warn
        else -> Status.ok
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${toGb(usedBytes)} / ${toGb(totalBytes)} GB",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        LinearProgressIndicator(
            progress = { fraction },
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().height(Spacing.sm),
        )
    }
}

private fun toGb(bytes: Long): String = String.format("%.1f", bytes / 1024.0 / 1024.0 / 1024.0)

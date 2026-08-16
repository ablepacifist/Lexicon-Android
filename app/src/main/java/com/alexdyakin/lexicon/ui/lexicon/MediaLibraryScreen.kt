package com.alexdyakin.lexicon.ui.lexicon

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.alexdyakin.lexicon.R
import com.alexdyakin.lexicon.data.MediaFile
import com.alexdyakin.lexicon.data.MediaKind
import com.alexdyakin.lexicon.ui.components.EmptyBox
import com.alexdyakin.lexicon.ui.components.LoadingBox
import com.alexdyakin.lexicon.ui.components.ScreenScaffold

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MediaLibraryScreen(
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenUpload: () -> Unit,
    title: String = "Library",
    pinnedFilter: MediaFilter? = null,
    showUploadAction: Boolean = true,
    viewModel: MediaViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val playerState by viewModel.player.state.collectAsState()
    var editing by remember { mutableStateOf<MediaFile?>(null) }
    var deleting by remember { mutableStateOf<MediaFile?>(null) }

    LaunchedEffect(pinnedFilter) {
        if (pinnedFilter != null) viewModel.setFilter(pinnedFilter)
    }

    if (editing != null) {
        EditMediaDialog(
            media = editing!!,
            saving = state.saving,
            onDismiss = { editing = null },
            onSave = { title, description -> viewModel.updateMedia(editing!!.id, title, description); editing = null },
        )
    }
    if (deleting != null) {
        ConfirmMediaDeleteDialog(
            media = deleting!!,
            saving = state.saving,
            onDismiss = { deleting = null },
            onConfirm = { viewModel.deleteMedia(deleting!!.id); deleting = null },
        )
    }

    ScreenScaffold(
        title = title,
        onBack = onBack,
        background = R.drawable.bg_dashboard,
        actions = { if (showUploadAction) TextButton(onClick = onOpenUpload) { Text("Add") } },
        bottomBar = {
            // Mini-player docks above the content whenever something is loaded
            AnimatedVisibility(
                visible = playerState.mediaId != null,
                enter = slideInVertically { it } + fadeIn(),
            ) {
                MiniPlayer(
                    state = playerState,
                    onToggle = viewModel.player::togglePlayPause,
                    onNext = viewModel.player::next,
                    onClick = onOpenPlayer,
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                placeholder = { Text("Search the library") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )

            FlowRow(
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (pinnedFilter == null) {
                    MediaFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = state.filter == filter,
                            onClick = { viewModel.setFilter(filter) },
                            label = { Text(filter.label) },
                        )
                    }
                } else {
                    AssistChip(onClick = { }, label = { Text(pinnedFilter.label) })
                }
            }

            when {
                state.loading -> LoadingBox()
                state.error != null -> EmptyBox(message = state.error!!)
                state.visible.isEmpty() -> EmptyBox(
                    message = if (state.query.isBlank()) "Nothing in this section yet."
                              else "No matches for \"${state.query}\"."
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(state.visible, key = { _, m -> m.id }) { index, media ->
                        var visible by remember(media.id) { mutableStateOf(false) }
                        LaunchedEffect(media.id) { visible = true }

                        AnimatedVisibility(
                            visible = visible,
                            enter = fadeIn() + slideInVertically(
                                spring(
                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                    stiffness = Spring.StiffnessLow,
                                )
                            ) { it / (2 + index % 4) },
                        ) {
                            MediaRow(
                                media = media,
                                nowPlaying = playerState.mediaId == media.id.toString(),
                                onPlay = { viewModel.play(media); if (media.kind == MediaKind.VIDEO) onOpenPlayer() },
                                onEdit = { editing = media },
                                onDelete = { deleting = media },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AudioPlayerLibraryScreen(onBack: () -> Unit, onOpenPlayer: () -> Unit) = MediaLibraryScreen(
    onBack = onBack,
    onOpenPlayer = onOpenPlayer,
    onOpenUpload = onBack,
    title = "Audio player",
    pinnedFilter = MediaFilter.MUSIC,
    showUploadAction = false,
)

@Composable
fun VideoPlayerLibraryScreen(onBack: () -> Unit, onOpenPlayer: () -> Unit) = MediaLibraryScreen(
    onBack = onBack,
    onOpenPlayer = onOpenPlayer,
    onOpenUpload = onBack,
    title = "Video player",
    pinnedFilter = MediaFilter.VIDEO,
    showUploadAction = false,
)

@Composable
fun AudiobooksLibraryScreen(onBack: () -> Unit, onOpenPlayer: () -> Unit) = MediaLibraryScreen(
    onBack = onBack,
    onOpenPlayer = onOpenPlayer,
    onOpenUpload = onBack,
    title = "Audiobooks",
    pinnedFilter = MediaFilter.AUDIOBOOKS,
    showUploadAction = false,
)

@Composable
private fun MediaRow(
    media: MediaFile,
    nowPlaying: Boolean,
    onPlay: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (nowPlaying)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f)
            else MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = media.isPlayable, onClick = onPlay),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = when (media.kind) {
                    MediaKind.AUDIO -> "♪"
                    MediaKind.AUDIOBOOK -> "📖"
                    MediaKind.VIDEO -> "▶"
                    MediaKind.IMAGE -> "◧"
                    MediaKind.OTHER -> "◈"
                },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(40.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    media.displayTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(media.mediaType.lowercase().ifBlank { "file" })
                        if (media.fileSize > 0) append(" · %.1f MB".format(media.fileSize / 1_048_576.0))
                        if (nowPlaying) append(" · playing")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (media.isPlayable) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "Play",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "Edit") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun EditMediaDialog(
    media: MediaFile,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var title by remember(media.id) { mutableStateOf(media.title.ifBlank { media.displayTitle }) }
    var description by remember(media.id) { mutableStateOf(media.description) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit media") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, singleLine = true)
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description") })
            }
        },
        confirmButton = {
            Button(onClick = { onSave(title, description) }, enabled = !saving && title.isNotBlank()) { Text("Save") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") } },
    )
}

@Composable
private fun ConfirmMediaDeleteDialog(
    media: MediaFile,
    saving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Delete media?") },
    text = { Text("Delete \"${media.displayTitle}\" from the library?") },
    confirmButton = { Button(onClick = onConfirm, enabled = !saving) { Text("Delete") } },
    dismissButton = { OutlinedButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") } },
)

@Composable
fun MiniPlayer(
    state: com.alexdyakin.lexicon.media.PlayerState,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        tonalElevation = 8.dp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column {
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent,
            )
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        state.title.ifBlank { "Nothing playing" },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        state.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onToggle) {
                    Text(
                        if (state.isPlaying) "⏸" else "▶",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = onNext, enabled = state.hasNext) {
                    Text(
                        "⏭",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

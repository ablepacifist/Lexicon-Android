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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import com.alexdyakin.lexicon.data.MediaFile
import com.alexdyakin.lexicon.data.MediaKind
import com.alexdyakin.lexicon.ui.components.ActionRow
import com.alexdyakin.lexicon.ui.components.EmptyBox
import com.alexdyakin.lexicon.ui.components.LexButton
import com.alexdyakin.lexicon.ui.components.LexChip
import com.alexdyakin.lexicon.ui.components.LexIconButton
import com.alexdyakin.lexicon.ui.components.LexTone
import com.alexdyakin.lexicon.ui.components.ListRow
import com.alexdyakin.lexicon.ui.components.LoadingBox
import com.alexdyakin.lexicon.ui.components.ScreenScaffold
import com.alexdyakin.lexicon.ui.theme.Elevation
import com.alexdyakin.lexicon.ui.theme.Radii
import com.alexdyakin.lexicon.ui.theme.Sizes
import com.alexdyakin.lexicon.ui.theme.Spacing

/** One icon per media kind, replacing the `♪ 📖 ▶ ◧ ◈` glyph column. */
private fun MediaKind.icon(): ImageVector = when (this) {
    MediaKind.AUDIO -> Icons.Filled.MusicNote
    MediaKind.AUDIOBOOK -> Icons.AutoMirrored.Filled.MenuBook
    MediaKind.VIDEO -> Icons.Filled.Movie
    MediaKind.IMAGE -> Icons.Filled.Image
    MediaKind.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
}

@OptIn(ExperimentalMaterial3Api::class)
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

    editing?.let { media ->
        EditMediaDialog(
            media = media,
            saving = state.saving,
            onDismiss = { editing = null },
            onSave = { newTitle, description ->
                viewModel.updateMedia(media.id, newTitle, description)
                editing = null
            },
        )
    }
    deleting?.let { media ->
        ConfirmMediaDeleteDialog(
            media = media,
            saving = state.saving,
            onDismiss = { deleting = null },
            onConfirm = {
                viewModel.deleteMedia(media.id)
                deleting = null
            },
        )
    }

    ScreenScaffold(
        title = title,
        onBack = onBack,
        gutter = Spacing.gutter,
        actions = {
            if (showUploadAction) {
                LexIconButton(
                    icon = Icons.Filled.Add,
                    contentDescription = "Add media",
                    onClick = onOpenUpload,
                )
            }
        },
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
        Column(
            Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                placeholder = { Text("Search the library") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.sm),
            )

            // A FlowRow, so four filters fit at 320dp and at a large font scale by wrapping
            // instead of running off the right edge.
            if (pinnedFilter == null) {
                ActionRow {
                    MediaFilter.entries.forEach { filter ->
                        LexChip(
                            label = filter.label,
                            selected = state.filter == filter,
                            onClick = { viewModel.setFilter(filter) },
                        )
                    }
                }
            } else {
                ActionRow {
                    AssistChip(
                        onClick = { },
                        shape = Radii.pill,
                        label = { Text(pinnedFilter.label, maxLines = 1) },
                    )
                }
            }

            when {
                state.loading -> LoadingBox()
                state.error != null -> EmptyBox(message = state.error!!)
                state.visible.isEmpty() -> EmptyBox(
                    message = if (state.query.isBlank()) {
                        "Nothing in this section yet."
                    } else {
                        "No matches for \"${state.query}\"."
                    }
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(bottom = Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
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
                                onPlay = {
                                    viewModel.play(media)
                                    if (media.kind == MediaKind.VIDEO) onOpenPlayer()
                                },
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

/**
 * A library row.
 *
 * The trailing controls used to be three: a decorative `PlayArrow` that looked like a button but
 * was not one, then Edit, then Delete. With a 40dp glyph column that is 160dp of fixed children
 * plus padding against a 288dp content width, which left the title about 100dp — and the fake play
 * icon was the least useful of the three, since tapping the card is what starts playback. It is
 * gone, and the two real actions sit in [ListRow]'s fixed slot so a row cannot shift sideways.
 */
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
            containerColor = if (nowPlaying) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            }
        ),
        shape = Radii.card,
        elevation = CardDefaults.cardElevation(defaultElevation = Elevation.card),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = media.isPlayable, onClick = onPlay),
    ) {
        ListRow(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            action = {
                LexIconButton(
                    icon = Icons.Filled.Edit,
                    contentDescription = "Edit",
                    onClick = onEdit,
                )
                LexIconButton(
                    icon = Icons.Filled.Delete,
                    contentDescription = "Delete",
                    onClick = onDelete,
                    tone = LexTone.Destructive,
                )
            },
        ) {
            Icon(
                media.kind.icon(),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(Sizes.icon),
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
                        if (media.fileSize > 0) {
                            append(" · %.1f MB".format(media.fileSize / 1_048_576.0))
                        }
                        if (nowPlaying) append(" · playing")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
        shape = Radii.card,
        title = { Text("Edit media") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            LexButton(
                text = "Save",
                onClick = { onSave(title, description) },
                tone = LexTone.Primary,
                enabled = !saving && title.isNotBlank(),
                loading = saving,
            )
        },
        dismissButton = {
            LexButton("Cancel", onDismiss, tone = LexTone.Quiet, enabled = !saving)
        },
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
    shape = Radii.card,
    title = { Text("Delete media?") },
    text = { Text("Delete \"${media.displayTitle}\" from the library?") },
    // Destructive, so outlined in the error colour rather than the heaviest control in the dialog.
    confirmButton = {
        LexButton("Delete", onConfirm, tone = LexTone.Destructive, enabled = !saving)
    },
    dismissButton = {
        LexButton("Cancel", onDismiss, tone = LexTone.Quiet, enabled = !saving)
    },
)

@Composable
fun MiniPlayer(
    state: com.alexdyakin.lexicon.media.PlayerState,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = Elevation.raised,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column {
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth().height(Spacing.xxs),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent,
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm)
                    .heightIn(min = Sizes.touch),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
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
                // These were the emoji "⏸"/"▶"/"⏭" — glyphs that do not tint, do not scale with
                // the icon size, carry no content description, and whose width varies by device
                // font. The same vectors every other transport uses now.
                LexIconButton(
                    icon = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                    onClick = onToggle,
                    tone = LexTone.Primary,
                )
                LexIconButton(
                    icon = Icons.Filled.SkipNext,
                    contentDescription = "Next",
                    onClick = onNext,
                    enabled = state.hasNext,
                    tone = LexTone.Primary,
                )
            }
        }
    }
}

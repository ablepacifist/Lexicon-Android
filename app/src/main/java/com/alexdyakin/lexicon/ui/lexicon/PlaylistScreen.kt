package com.alexdyakin.lexicon.ui.lexicon

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexdyakin.lexicon.data.ApiUrls
import com.alexdyakin.lexicon.data.ApiResult
import com.alexdyakin.lexicon.data.MediaFile
import com.alexdyakin.lexicon.data.MediaKind
import com.alexdyakin.lexicon.data.Playlist
import com.alexdyakin.lexicon.data.PlaylistImportCompleted
import com.alexdyakin.lexicon.data.PlaylistImportProgress
import com.alexdyakin.lexicon.data.PlaylistItem
import com.alexdyakin.lexicon.data.PlaylistItemRequest
import com.alexdyakin.lexicon.data.PlaylistReorderRequest
import com.alexdyakin.lexicon.data.TokenStore
import com.alexdyakin.lexicon.data.api.MediaApi
import com.alexdyakin.lexicon.data.api.PlaybackApi
import com.alexdyakin.lexicon.data.api.PlaylistApi
import com.alexdyakin.lexicon.data.safeApiCall
import com.alexdyakin.lexicon.data.di.SseOkHttp
import com.alexdyakin.lexicon.media.PlayerConnection
import com.alexdyakin.lexicon.ui.components.ActionRow
import com.alexdyakin.lexicon.ui.components.EmptyBox
import com.alexdyakin.lexicon.ui.components.LexButton
import com.alexdyakin.lexicon.ui.components.LexChip
import com.alexdyakin.lexicon.ui.components.LexIconButton
import com.alexdyakin.lexicon.ui.components.LexTone
import com.alexdyakin.lexicon.ui.components.ListRow
import com.alexdyakin.lexicon.ui.components.LoadingBox
import com.alexdyakin.lexicon.ui.components.ScreenScaffold
import com.alexdyakin.lexicon.ui.components.SectionHeader
import com.alexdyakin.lexicon.ui.theme.Elevation
import com.alexdyakin.lexicon.ui.theme.Radii
import com.alexdyakin.lexicon.ui.theme.Sizes
import com.alexdyakin.lexicon.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import javax.inject.Inject

enum class PlaylistType(val wire: String, val label: String) {
    ALL("", "All"), MUSIC("MUSIC", "Music"), VIDEO("VIDEO", "Video"), AUDIOBOOK("AUDIOBOOK", "Books");
}

data class PlaylistUiState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    val currentUserId: Int = -1,
    val mine: List<Playlist> = emptyList(),
    val public: List<Playlist> = emptyList(),
    val detail: Playlist? = null,
    val availableMedia: List<MediaFile> = emptyList(),
    val type: PlaylistType = PlaylistType.ALL,
    val importing: Boolean = false,
    val importProgress: PlaylistImportProgress? = null,
    val error: String? = null,
)

@HiltViewModel
class PlaylistViewModel @Inject constructor(
    private val playlistApi: PlaylistApi,
    private val mediaApi: MediaApi,
    private val playbackApi: PlaybackApi,
    private val tokenStore: TokenStore,
    val player: PlayerConnection,
    @SseOkHttp private val sseClient: OkHttpClient,
    private val json: Json,
) : ViewModel() {
    private val _state = MutableStateFlow(PlaylistUiState(currentUserId = tokenStore.userId))
    val state: StateFlow<PlaylistUiState> = _state.asStateFlow()
    private var importSource: EventSource? = null

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = null)
        val mine = safeApiCall { playlistApi.byUser(tokenStore.userId) }
        val public = safeApiCall { playlistApi.public() }
        val media = safeApiCall { mediaApi.recent(200) }
        _state.value = _state.value.copy(
            loading = false,
            mine = mine.successOrNull.orEmpty(),
            public = public.successOrNull.orEmpty(),
            availableMedia = media.successOrNull.orEmpty(),
            error = (mine as? ApiResult.Failure)?.message ?: (public as? ApiResult.Failure)?.message,
        )
    }

    fun setType(type: PlaylistType) { _state.value = _state.value.copy(type = type) }

    fun open(id: Int) = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = null)
        when (val result = safeApiCall { playlistApi.byId(id) }) {
            is ApiResult.Success -> _state.value = _state.value.copy(loading = false, detail = result.data)
            is ApiResult.Failure -> _state.value = _state.value.copy(loading = false, error = result.message)
            ApiResult.Unauthorized -> _state.value = _state.value.copy(loading = false, error = "Sign in again to manage playlists.")
        }
    }

    fun closeDetail() { _state.value = _state.value.copy(detail = null, error = null) }

    fun save(playlist: Playlist) = viewModelScope.launch {
        _state.value = _state.value.copy(saving = true, error = null)
        val result = if (playlist.id == 0) {
            safeApiCall { playlistApi.create(tokenStore.userId, playlist) }
        } else {
            safeApiCall { playlistApi.update(playlist.id, tokenStore.userId, playlist) }
        }
        when (result) {
            is ApiResult.Success -> { refresh(); open(result.data.id) }
            is ApiResult.Failure -> _state.value = _state.value.copy(saving = false, error = result.message)
            ApiResult.Unauthorized -> _state.value = _state.value.copy(saving = false, error = "Only the playlist owner can make that change.")
        }
    }

    fun delete(playlist: Playlist) = viewModelScope.launch {
        _state.value = _state.value.copy(saving = true)
        when (val result = safeApiCall { playlistApi.delete(playlist.id, tokenStore.userId) }) {
            is ApiResult.Success -> { _state.value = _state.value.copy(detail = null); refresh() }
            is ApiResult.Failure -> _state.value = _state.value.copy(saving = false, error = result.message)
            ApiResult.Unauthorized -> _state.value = _state.value.copy(saving = false, error = "Only the playlist owner can delete it.")
        }
    }

    fun add(media: MediaFile) = mutateDetail { id -> playlistApi.addItem(id, tokenStore.userId, PlaylistItemRequest(media.id)) }
    fun remove(mediaFileId: Int) = mutateDetail { id -> playlistApi.removeItem(id, mediaFileId, tokenStore.userId) }

    fun move(index: Int, delta: Int) {
        val playlist = _state.value.detail ?: return
        val target = index + delta
        if (target !in playlist.items.indices) return
        val reordered = playlist.items.toMutableList().also { item ->
            val moved = item.removeAt(index)
            item.add(target, moved)
        }
        _state.value = _state.value.copy(detail = playlist.copy(items = reordered))
        mutateDetail { id -> playlistApi.reorder(id, tokenStore.userId, PlaylistReorderRequest(reordered.map { it.mediaFileId })) }
    }

    fun play(item: PlaylistItem) = viewModelScope.launch {
        val detail = _state.value.detail ?: return@launch
        val queue = detail.items.map { it.mediaFile }.filter { it.isPlayable }
        val index = queue.indexOfFirst { it.id == item.mediaFileId }
        if (index < 0) return@launch
        val target = queue[index]
        val resumeMs = if (target.kind == MediaKind.AUDIOBOOK) {
            when (val result = safeApiCall { playbackApi.position(tokenStore.userId, target.id) }) {
                is ApiResult.Success -> result.data.takeIf { it.found && !it.completed && it.position > 0.0 }?.position?.times(1_000)?.toLong() ?: 0L
                else -> 0L
            }
        } else 0L
        player.play(queue, index, resumeMs)
    }

    private fun mutateDetail(call: suspend (Int) -> Unit) = viewModelScope.launch {
        val id = _state.value.detail?.id ?: return@launch
        _state.value = _state.value.copy(saving = true, error = null)
        when (val result = safeApiCall { call(id) }) {
            is ApiResult.Success -> open(id)
            is ApiResult.Failure -> _state.value = _state.value.copy(saving = false, error = result.message)
            ApiResult.Unauthorized -> _state.value = _state.value.copy(saving = false, error = "Only the playlist owner can make that change.")
        }
    }

    fun startYoutubeImport(url: String, playlistName: String, playlistPublic: Boolean, mediaPublic: Boolean, mediaType: String, downloadType: String) = viewModelScope.launch {
        _state.value = _state.value.copy(importing = true, importProgress = PlaylistImportProgress(message = "Starting import..."), error = null)
        when (val result = safeApiCall { playlistApi.importYoutube(url, tokenStore.userId, playlistName, playlistPublic, mediaPublic, mediaType, downloadType) }) {
            is ApiResult.Success -> connectImportProgress(result.data.importId)
            is ApiResult.Failure -> _state.value = _state.value.copy(importing = false, error = result.message)
            ApiResult.Unauthorized -> _state.value = _state.value.copy(importing = false, error = "Sign in again to import playlists.")
        }
    }

    private fun connectImportProgress(importId: String) {
        importSource?.cancel()
        val request = Request.Builder().url("${ApiUrls.LEXICON}api/playlists/import-progress/$importId").build()
        importSource = EventSources.createFactory(sseClient).newEventSource(request, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return
                when (type) {
                    "progress" -> {
                        val progress = PlaylistImportProgress(
                            message = obj["message"]?.jsonPrimitive?.content.orEmpty(),
                            total = obj["total"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                            successful = obj["successful"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                            failed = obj["failed"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                            processed = obj["processed"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                            percentage = obj["percentage"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                        )
                        _state.value = _state.value.copy(importProgress = progress)
                    }
                    "completed" -> {
                        val completed = PlaylistImportCompleted(
                            playlistId = obj["playlistId"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                            totalTracks = obj["totalTracks"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                            successfulTracks = obj["successfulTracks"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                            failedTracks = obj["failedTracks"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                            message = obj["message"]?.jsonPrimitive?.content.orEmpty(),
                        )
                        _state.value = _state.value.copy(importing = false, importProgress = null, error = null)
                        viewModelScope.launch { refresh(); if (completed.playlistId > 0) open(completed.playlistId) }
                    }
                    "error" -> _state.value = _state.value.copy(importing = false, importProgress = null, error = obj["message"]?.jsonPrimitive?.content.orEmpty())
                }
            }
        })
    }

    override fun onCleared() {
        importSource?.cancel()
        super.onCleared()
    }
}

@Composable
fun PlaylistScreen(onBack: () -> Unit, viewModel: PlaylistViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var editor by remember { mutableStateOf<Playlist?>(null) }
    var adding by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf<Playlist?>(null) }
    val detail = state.detail

    editor?.let { PlaylistEditorDialog(it, state.saving, { editor = null }, viewModel::save) }
    if (adding && detail != null) {
        AddMediaDialog(
            media = state.availableMedia.filter {
                it.isPlayable && it.id !in detail.items.map { item -> item.mediaFileId }
            },
            playlistType = detail.mediaType,
            onDismiss = { adding = false },
            onAdd = { viewModel.add(it); adding = false },
        )
    }
    deleteConfirm?.let { target ->
        ConfirmDeleteDialog(target, { deleteConfirm = null }) {
            viewModel.delete(it)
            deleteConfirm = null
        }
    }
    if (importing) {
        YoutubeImportDialog(
            inProgress = state.importing,
            progress = state.importProgress,
            onDismiss = { if (!state.importing) importing = false },
            onStart = { url, name, playlistPublic, mediaPublic, mediaType, downloadType ->
                viewModel.startYoutubeImport(url, name, playlistPublic, mediaPublic, mediaType, downloadType)
            },
        )
    }

    ScreenScaffold(
        title = detail?.name ?: "Playlists",
        onBack = { if (detail != null) viewModel.closeDetail() else onBack() },
        gutter = Spacing.gutter,
    ) { padding ->
        if (state.loading) {
            LoadingBox(padding)
            return@ScreenScaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            state.error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
            }
            if (detail == null) {
                // These were `AssistChip`s, which have no selected state — so the active filter
                // was invisible and all four looked identical whatever was chosen.
                ActionRow(Modifier.padding(top = Spacing.sm)) {
                    PlaylistType.entries.forEach { type ->
                        LexChip(
                            label = type.label,
                            selected = state.type == type,
                            onClick = { viewModel.setType(type) },
                        )
                    }
                }
                PlaylistCatalog(
                    state = state,
                    onOpen = viewModel::open,
                    onCreate = { editor = Playlist() },
                    onImport = { importing = true },
                )
            } else {
                PlaylistDetail(
                    playlist = detail,
                    currentUserId = state.currentUserId,
                    saving = state.saving,
                    onPlay = viewModel::play,
                    onAdd = { adding = true },
                    onEdit = { editor = detail },
                    onDelete = { deleteConfirm = detail },
                    onRemove = viewModel::remove,
                    onMove = viewModel::move,
                )
            }
        }
    }
}

@Composable
private fun PlaylistCatalog(
    state: PlaylistUiState,
    onOpen: (Int) -> Unit,
    onCreate: () -> Unit,
    onImport: () -> Unit,
) {
    val type = state.type.wire
    val mine = state.mine.filter { type.isBlank() || it.mediaType.equals(type, true) }
    val public = state.public.filter {
        it.id !in state.mine.map { own -> own.id } &&
            (type.isBlank() || it.mediaType.equals(type, true))
    }
    Box(Modifier.fillMaxSize()) {
        if (mine.isEmpty() && public.isEmpty()) {
            EmptyBox(message = "No playlists match this filter.")
        } else {
            LazyColumn(
                // Clears the two stacked action buttons, which used to sit on top of the last row.
                contentPadding = PaddingValues(bottom = Sizes.touch * 3),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                if (state.importing || state.importProgress != null) {
                    item { ImportProgressCard(state.importProgress) }
                }
                item { SectionHeader("Your playlists") }
                if (mine.isEmpty()) {
                    // The header used to sit directly on top of "Public playlists" with nothing
                    // between them, which read as a rendering fault rather than an empty section.
                    item {
                        Text(
                            "You have not made one yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                itemsIndexed(mine, key = { _, item -> "mine-${item.id}" }) { _, playlist ->
                    PlaylistCard(playlist, true) { onOpen(playlist.id) }
                }
                if (public.isNotEmpty()) {
                    item { SectionHeader("Public playlists") }
                    itemsIndexed(public, key = { _, item -> "public-${item.id}" }) { _, playlist ->
                        PlaylistCard(playlist, false) { onOpen(playlist.id) }
                    }
                }
            }
        }
        Column(
            Modifier.align(Alignment.BottomEnd).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            // Was a FAB containing the text "YT".
            SmallFloatingActionButton(onClick = onImport) {
                Icon(Icons.Filled.SmartDisplay, contentDescription = "Import from YouTube")
            }
            FloatingActionButton(onClick = onCreate) {
                Icon(Icons.Filled.Add, contentDescription = "Create playlist")
            }
        }
    }
}

@Composable
private fun ImportProgressCard(progress: PlaylistImportProgress?) {
    Card(
        shape = Radii.card,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(
                "Importing YouTube playlist",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                progress?.message ?: "Preparing import...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val percent = (progress?.percentage ?: 0).coerceIn(0, 100)
            LinearProgressIndicator(
                progress = { percent / 100f },
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs),
            )
            Text(
                "$percent%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun PlaylistCard(playlist: Playlist, owned: Boolean, onClick: () -> Unit) {
    Card(
        shape = Radii.card,
        elevation = CardDefaults.cardElevation(defaultElevation = Elevation.card),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        ListRow(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            actionSlotWidth = Sizes.icon,
            action = {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(Sizes.icon),
                )
            },
        ) {
            Icon(
                if (playlist.mediaType.equals("VIDEO", true)) {
                    Icons.Filled.Movie
                } else {
                    Icons.Filled.MusicNote
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(Sizes.icon),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    playlist.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    playlist.description.ifBlank { "No description" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(playlist.mediaType.lowercase())
                        append(" · ${playlist.itemCount} items · ")
                        append(if (playlist.isPublic) "public" else "private")
                        if (owned) append(" · yours")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PlaylistDetail(
    playlist: Playlist,
    currentUserId: Int,
    saving: Boolean,
    onPlay: (PlaylistItem) -> Unit,
    onAdd: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
) {
    val owns = currentUserId == playlist.createdBy && playlist.createdBy > 0
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            playlist.description.ifBlank { "No description" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${playlist.mediaType.lowercase()} · ${if (playlist.isPublic) "public" else "private"}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (owns) {
            // Add media + Edit + a delete icon is ~300dp of fixed children, which overflowed the
            // 288dp a 320dp phone has. A FlowRow wraps the third control onto its own line.
            ActionRow {
                LexButton("Add media", onAdd, tone = LexTone.Primary, icon = Icons.Filled.Add, enabled = !saving)
                LexButton("Edit", onEdit, icon = Icons.Filled.Edit, enabled = !saving)
                LexIconButton(
                    icon = Icons.Filled.Delete,
                    contentDescription = "Delete playlist",
                    onClick = onDelete,
                    enabled = !saving,
                    tone = LexTone.Destructive,
                )
            }
        }
        if (playlist.items.isEmpty()) {
            EmptyBox(message = "This playlist is empty.")
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                itemsIndexed(playlist.items, key = { _, item -> item.mediaFileId }) { index, item ->
                    PlaylistItemCard(
                        item = item,
                        index = index,
                        lastIndex = playlist.items.lastIndex,
                        owns = owns,
                        saving = saving,
                        onPlay = { onPlay(item) },
                        onRemove = { onRemove(item.mediaFileId) },
                        onMove = onMove,
                    )
                }
            }
        }
    }
}

/**
 * A playlist track.
 *
 * The owner view used to be five controls in one row — play, then move-up, move-down and remove,
 * around a `weight(1f)` title. That is 192dp of fixed 48dp slots plus padding against a 288dp
 * content width, leaving the title about 76dp, roughly six characters. The management controls now
 * sit on their own wrapping row below the title, so the title gets the full width and nothing is
 * squeezed; a non-owner sees exactly one row, as before.
 */
@Composable
private fun PlaylistItemCard(
    item: PlaylistItem,
    index: Int,
    lastIndex: Int,
    owns: Boolean,
    saving: Boolean,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    onMove: (Int, Int) -> Unit,
) {
    Card(
        shape = Radii.card,
        elevation = CardDefaults.cardElevation(defaultElevation = Elevation.card),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            ListRow(
                actionSlotWidth = Sizes.touch,
                action = {
                    LexIconButton(
                        icon = Icons.Filled.PlayArrow,
                        contentDescription = "Play",
                        onClick = onPlay,
                        tone = LexTone.Primary,
                    )
                },
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        item.mediaFile.displayTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        item.mediaFile.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (owns) {
                ActionRow(horizontalAlignment = Alignment.End) {
                    LexIconButton(
                        icon = Icons.Filled.ArrowUpward,
                        contentDescription = "Move earlier",
                        onClick = { onMove(index, -1) },
                        enabled = index > 0 && !saving,
                    )
                    LexIconButton(
                        icon = Icons.Filled.ArrowDownward,
                        contentDescription = "Move later",
                        onClick = { onMove(index, 1) },
                        enabled = index < lastIndex && !saving,
                    )
                    LexIconButton(
                        icon = Icons.Filled.Delete,
                        contentDescription = "Remove from playlist",
                        onClick = onRemove,
                        enabled = !saving,
                        tone = LexTone.Destructive,
                    )
                }
            }
        }
    }
}

@Composable
private fun YoutubeImportDialog(
    inProgress: Boolean,
    progress: PlaylistImportProgress?,
    onDismiss: () -> Unit,
    onStart: (String, String, Boolean, Boolean, String, String) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var playlistPublic by remember { mutableStateOf(true) }
    var mediaPublic by remember { mutableStateOf(false) }
    var mediaType by remember { mutableStateOf("MUSIC") }
    var downloadType by remember { mutableStateOf("AUDIO_ONLY") }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = Radii.card,
        title = { Text("Import YouTube playlist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                DialogField(url, { url = it }, "Playlist URL", singleLine = true)
                DialogField(name, { name = it }, "Playlist name", singleLine = true)
                CheckRow("Playlist is public", playlistPublic) { playlistPublic = it }
                CheckRow("Imported media is public", mediaPublic) { mediaPublic = it }
                // Two radio buttons and their labels in a bare Row; chips state the same choice
                // in half the width and wrap if the font scale needs them to.
                ActionRow {
                    listOf("MUSIC", "VIDEO").forEach { option ->
                        LexChip(
                            label = option.lowercase().replaceFirstChar { it.uppercase() },
                            selected = mediaType == option,
                            onClick = {
                                mediaType = option
                                if (option == "VIDEO") downloadType = "VIDEO"
                            },
                        )
                    }
                }
                if (mediaType == "MUSIC") {
                    ActionRow {
                        listOf("AUDIO_ONLY", "VIDEO").forEach { option ->
                            LexChip(
                                label = option.replace('_', ' ').lowercase()
                                    .replaceFirstChar { it.uppercase() },
                                selected = downloadType == option,
                                onClick = { downloadType = option },
                            )
                        }
                    }
                }
                if (progress != null) {
                    Text(
                        progress.message,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    LinearProgressIndicator(
                        progress = { progress.percentage.coerceIn(0, 100) / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            LexButton(
                text = if (inProgress) "Importing..." else "Start",
                onClick = {
                    onStart(
                        url.trim(),
                        name.trim().ifBlank { "YouTube import" },
                        playlistPublic,
                        mediaPublic,
                        mediaType,
                        downloadType,
                    )
                },
                tone = LexTone.Primary,
                enabled = !inProgress && url.isNotBlank(),
            )
        },
        dismissButton = { LexButton("Close", onDismiss, tone = LexTone.Quiet, enabled = !inProgress) },
    )
}

@Composable
private fun PlaylistEditorDialog(
    playlist: Playlist,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (Playlist) -> Unit,
) {
    var name by remember(playlist.id) { mutableStateOf(playlist.name) }
    var description by remember(playlist.id) { mutableStateOf(playlist.description) }
    var isPublic by remember(playlist.id) { mutableStateOf(playlist.isPublic) }
    var type by remember(playlist.id) { mutableStateOf(playlist.mediaType.ifBlank { "MUSIC" }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = Radii.card,
        title = { Text(if (playlist.id == 0) "Create playlist" else "Edit playlist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                DialogField(name, { name = it }, "Name", singleLine = true)
                DialogField(description, { description = it }, "Description")
                CheckRow("Public playlist", isPublic) { isPublic = it }
                // Was one stacked radio row per type, each 48dp tall.
                ActionRow {
                    PlaylistType.entries.filter { it != PlaylistType.ALL }.forEach { option ->
                        LexChip(
                            label = option.label,
                            selected = type == option.wire,
                            onClick = { type = option.wire },
                        )
                    }
                }
            }
        },
        confirmButton = {
            LexButton(
                text = "Save",
                onClick = {
                    onSave(
                        playlist.copy(
                            name = name.trim(),
                            description = description.trim(),
                            isPublic = isPublic,
                            mediaType = type,
                        )
                    )
                },
                tone = LexTone.Primary,
                enabled = name.isNotBlank() && !saving,
                loading = saving,
            )
        },
        dismissButton = { LexButton("Cancel", onDismiss, tone = LexTone.Quiet, enabled = !saving) },
    )
}

@Composable
private fun AddMediaDialog(
    media: List<MediaFile>,
    playlistType: String,
    onDismiss: () -> Unit,
    onAdd: (MediaFile) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val compatible = media.filter { item ->
        (playlistType.equals("MUSIC", true) && item.kind == MediaKind.AUDIO ||
            playlistType.equals("VIDEO", true) && item.kind == MediaKind.VIDEO ||
            playlistType.equals("AUDIOBOOK", true) && item.kind == MediaKind.AUDIOBOOK) &&
            item.displayTitle.contains(query, true)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = Radii.card,
        title = { Text("Add media") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                DialogField(
                    query,
                    { query = it },
                    "Search ${playlistType.lowercase()}",
                    singleLine = true,
                )
                if (compatible.isEmpty()) {
                    Text(
                        "Nothing to add.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // Was a fixed height(300.dp), which cannot shrink on a short screen or at a
                    // large font scale. A max lets the dialog size itself to the list.
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        itemsIndexed(compatible, key = { _, item -> item.id }) { _, item ->
                            ListRow(
                                modifier = Modifier.clickable { onAdd(item) },
                                actionSlotWidth = Sizes.icon,
                                action = {
                                    Icon(
                                        Icons.Filled.Add,
                                        contentDescription = "Add",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(Sizes.icon),
                                    )
                                },
                            ) {
                                Text(
                                    item.displayTitle,
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { LexButton("Done", onDismiss, tone = LexTone.Primary) },
    )
}

@Composable
private fun ConfirmDeleteDialog(
    playlist: Playlist,
    onDismiss: () -> Unit,
    onConfirm: (Playlist) -> Unit,
) = AlertDialog(
    onDismissRequest = onDismiss,
    shape = Radii.card,
    title = { Text("Delete playlist?") },
    text = { Text("Delete “${playlist.name}”? The media files will be kept.") },
    confirmButton = {
        LexButton("Delete", { onConfirm(playlist) }, tone = LexTone.Destructive)
    },
    dismissButton = { LexButton("Cancel", onDismiss, tone = LexTone.Quiet) },
)

/** Every text field in this screen's dialogs: full width, one shape, one label treatment. */
@Composable
private fun DialogField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    singleLine: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = singleLine,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * A checkbox and its label as one tappable row. The label used to be a bare `Text` beside the
 * box — not clickable, and nothing holding the row to the 48dp touch minimum.
 */
@Composable
private fun CheckRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.touch)
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

package com.alexdyakin.lexicon.ui.lexicon

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexdyakin.lexicon.R
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
import com.alexdyakin.lexicon.ui.components.EmptyBox
import com.alexdyakin.lexicon.ui.components.LoadingBox
import com.alexdyakin.lexicon.ui.components.ScreenScaffold
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaylistScreen(onBack: () -> Unit, viewModel: PlaylistViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var editor by remember { mutableStateOf<Playlist?>(null) }
    var adding by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf<Playlist?>(null) }
    val detail = state.detail

    if (editor != null) PlaylistEditorDialog(editor!!, state.saving, { editor = null }, viewModel::save)
    if (adding && detail != null) AddMediaDialog(
        media = state.availableMedia.filter { it.isPlayable && it.id !in detail.items.map { item -> item.mediaFileId } },
        playlistType = detail.mediaType,
        onDismiss = { adding = false },
        onAdd = { viewModel.add(it); adding = false },
    )
    if (deleteConfirm != null) ConfirmDeleteDialog(deleteConfirm!!, { deleteConfirm = null }) {
        viewModel.delete(it); deleteConfirm = null
    }
    if (importing) YoutubeImportDialog(
        inProgress = state.importing,
        progress = state.importProgress,
        onDismiss = { if (!state.importing) importing = false },
        onStart = { url, name, playlistPublic, mediaPublic, mediaType, downloadType ->
            viewModel.startYoutubeImport(url, name, playlistPublic, mediaPublic, mediaType, downloadType)
        },
    )

    ScreenScaffold(
        title = detail?.name ?: "Playlists",
        onBack = { if (detail != null) viewModel.closeDetail() else onBack() },
        background = R.drawable.bg_dashboard,
    ) { padding ->
        if (state.loading) { LoadingBox(padding); return@ScreenScaffold }
        Column(Modifier.fillMaxSize().padding(padding)) {
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            if (detail == null) {
                FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlaylistType.entries.forEach { type ->
                        AssistChip(onClick = { viewModel.setType(type) }, label = { Text(type.label) })
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
private fun PlaylistCatalog(state: PlaylistUiState, onOpen: (Int) -> Unit, onCreate: () -> Unit, onImport: () -> Unit) {
    val type = state.type.wire
    val mine = state.mine.filter { type.isBlank() || it.mediaType.equals(type, true) }
    val public = state.public.filter { it.id !in state.mine.map { own -> own.id } && (type.isBlank() || it.mediaType.equals(type, true)) }
    Box(Modifier.fillMaxSize()) {
        if (mine.isEmpty() && public.isEmpty()) EmptyBox(message = "No playlists match this filter.")
        else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.importing || state.importProgress != null) {
                item {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f))) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Importing YouTube playlist", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                            Text(state.importProgress?.message ?: "Preparing import...", style = MaterialTheme.typography.bodySmall)
                            val progress = (state.importProgress?.percentage ?: 0).coerceIn(0, 100)
                            androidx.compose.material3.LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                            Text("$progress%", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
            item { Text("Your playlists", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
            itemsIndexed(mine, key = { _, item -> "mine-${item.id}" }) { _, playlist -> PlaylistCard(playlist, true) { onOpen(playlist.id) } }
            if (public.isNotEmpty()) {
                item { Text("Public playlists", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp)) }
                itemsIndexed(public, key = { _, item -> "public-${item.id}" }) { _, playlist -> PlaylistCard(playlist, false) { onOpen(playlist.id) } }
            }
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FloatingActionButton(onClick = onImport) { Text("YT") }
            FloatingActionButton(onClick = onCreate) { Icon(Icons.Default.Add, contentDescription = "Create playlist") }
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
        title = { Text("Import YouTube playlist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(url, { url = it }, label = { Text("Playlist URL") })
                OutlinedTextField(name, { name = it }, label = { Text("Playlist name") })
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(playlistPublic, { playlistPublic = it }); Text("Playlist is public") }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(mediaPublic, { mediaPublic = it }); Text("Imported media is public") }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("MUSIC", "VIDEO").forEach { t -> RadioButton(selected = mediaType == t, onClick = { mediaType = t; if (t == "VIDEO") downloadType = "VIDEO" }); Text(t.lowercase().replaceFirstChar { it.uppercase() }) }
                }
                if (mediaType == "MUSIC") Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("AUDIO_ONLY", "VIDEO").forEach { t -> RadioButton(selected = downloadType == t, onClick = { downloadType = t }); Text(t.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }) }
                }
                if (progress != null) {
                    Text(progress.message, style = MaterialTheme.typography.bodySmall)
                    androidx.compose.material3.LinearProgressIndicator(progress = { (progress.percentage.coerceIn(0, 100)) / 100f }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = { Button(onClick = { onStart(url.trim(), name.trim().ifBlank { "YouTube import" }, playlistPublic, mediaPublic, mediaType, downloadType) }, enabled = !inProgress && url.isNotBlank()) { Text(if (inProgress) "Importing..." else "Start") } },
        dismissButton = { OutlinedButton(onClick = onDismiss, enabled = !inProgress) { Text("Close") } },
    )
}

@Composable
private fun PlaylistCard(playlist: Playlist, owned: Boolean, onClick: () -> Unit) = Card(
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .92f)),
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
) {
    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (playlist.mediaType.equals("VIDEO", true)) "▶" else "♫", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(playlist.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(playlist.description.ifBlank { "No description" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            Text("${playlist.mediaType.lowercase()} · ${playlist.itemCount} items · ${if (playlist.isPublic) "public" else "private"}${if (owned) " · yours" else ""}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("›", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun PlaylistDetail(
    playlist: Playlist, currentUserId: Int, saving: Boolean, onPlay: (PlaylistItem) -> Unit,
    onAdd: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, onRemove: (Int) -> Unit, onMove: (Int, Int) -> Unit,
) {
    val owns = currentUserId == playlist.createdBy && playlist.createdBy > 0
    Column(Modifier.fillMaxSize()) {
        Text(playlist.description.ifBlank { "No description" }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.bodyMedium)
        Text("${playlist.mediaType.lowercase()} · ${if (playlist.isPublic) "public" else "private"}", modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (owns) Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onAdd, enabled = !saving) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Add media") }
            OutlinedButton(onClick = onEdit, enabled = !saving) { Icon(Icons.Default.Edit, null); Spacer(Modifier.width(4.dp)); Text("Edit") }
            IconButton(onClick = onDelete, enabled = !saving) { Icon(Icons.Default.Delete, "Delete playlist", tint = MaterialTheme.colorScheme.error) }
        }
        if (playlist.items.isEmpty()) EmptyBox(message = "This playlist is empty.") else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(playlist.items, key = { _, item -> item.mediaFileId }) { index, item ->
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onPlay(item) }) { Icon(Icons.Default.PlayArrow, "Play") }
                        Column(Modifier.weight(1f)) {
                            Text(item.mediaFile.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                            Text(item.mediaFile.description, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        }
                        if (owns) {
                            IconButton(onClick = { onMove(index, -1) }, enabled = index > 0 && !saving) { Icon(Icons.Default.ArrowUpward, "Move earlier") }
                            IconButton(onClick = { onMove(index, 1) }, enabled = index < playlist.items.lastIndex && !saving) { Icon(Icons.Default.ArrowDownward, "Move later") }
                            IconButton(onClick = { onRemove(item.mediaFileId) }, enabled = !saving) { Icon(Icons.Default.Delete, "Remove", tint = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistEditorDialog(playlist: Playlist, saving: Boolean, onDismiss: () -> Unit, onSave: (Playlist) -> Unit) {
    var name by remember(playlist.id) { mutableStateOf(playlist.name) }
    var description by remember(playlist.id) { mutableStateOf(playlist.description) }
    var isPublic by remember(playlist.id) { mutableStateOf(playlist.isPublic) }
    var type by remember(playlist.id) { mutableStateOf(playlist.mediaType.ifBlank { "MUSIC" }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (playlist.id == 0) "Create playlist" else "Edit playlist") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
            OutlinedTextField(description, { description = it }, label = { Text("Description") })
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(isPublic, { isPublic = it }); Text("Public playlist") }
            PlaylistType.entries.filter { it != PlaylistType.ALL }.forEach { option -> Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = type == option.wire, onClick = { type = option.wire }); Text(option.label)
            } }
        } },
        confirmButton = { Button(onClick = { onSave(playlist.copy(name = name.trim(), description = description.trim(), isPublic = isPublic, mediaType = type)) }, enabled = name.isNotBlank() && !saving) { Text("Save") } },
        dismissButton = { OutlinedButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") } },
    )
}

@Composable
private fun AddMediaDialog(media: List<MediaFile>, playlistType: String, onDismiss: () -> Unit, onAdd: (MediaFile) -> Unit) {
    var query by remember { mutableStateOf("") }
    val compatible = media.filter { item ->
        (playlistType.equals("MUSIC", true) && item.kind == MediaKind.AUDIO ||
            playlistType.equals("VIDEO", true) && item.kind == MediaKind.VIDEO ||
            playlistType.equals("AUDIOBOOK", true) && item.kind == MediaKind.AUDIOBOOK) &&
            item.displayTitle.contains(query, true)
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add media") }, text = { Column {
        OutlinedTextField(query, { query = it }, label = { Text("Search ${playlistType.lowercase()}") }, singleLine = true)
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.height(300.dp)) { itemsIndexed(compatible, key = { _, item -> item.id }) { _, item ->
            Row(Modifier.fillMaxWidth().clickable { onAdd(item) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(item.displayTitle, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Default.Add, "Add")
            }
        } }
    } }, confirmButton = { OutlinedButton(onClick = onDismiss) { Text("Done") } })
}

@Composable
private fun ConfirmDeleteDialog(playlist: Playlist, onDismiss: () -> Unit, onConfirm: (Playlist) -> Unit) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Delete playlist?") },
    text = { Text("Delete “${playlist.name}”? The media files will be kept.") },
    confirmButton = { Button(onClick = { onConfirm(playlist) }) { Text("Delete") } },
    dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
)
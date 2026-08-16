package com.alexdyakin.lexicon.ui.lexicon

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexdyakin.lexicon.R
import com.alexdyakin.lexicon.data.ApiResult
import com.alexdyakin.lexicon.data.ApiUrls
import com.alexdyakin.lexicon.data.LiveStreamQueueItem
import com.alexdyakin.lexicon.data.MediaFile
import com.alexdyakin.lexicon.data.Playlist
import com.alexdyakin.lexicon.data.QueueMediaRequest
import com.alexdyakin.lexicon.data.QueuePlaylistRequest
import com.alexdyakin.lexicon.data.SkipStreamRequest
import com.alexdyakin.lexicon.data.TokenStore
import com.alexdyakin.lexicon.data.api.LiveStreamApi
import com.alexdyakin.lexicon.data.api.MediaApi
import com.alexdyakin.lexicon.data.api.PlaylistApi
import com.alexdyakin.lexicon.data.di.SseOkHttp
import com.alexdyakin.lexicon.data.safeApiCall
import com.alexdyakin.lexicon.ui.components.OnScrim
import com.alexdyakin.lexicon.ui.components.OnScrimDim
import com.alexdyakin.lexicon.ui.components.ScreenScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import javax.inject.Inject

/**
 * Queue Manager — port of `Lexicon/src/pages/QueueManager.js`.
 *
 * The web page is a two-panel desktop layout (queue on the left, media browser on the right);
 * on a phone the same two panels are stacked. Unlike the stream screens, this one DOES switch
 * channels — that is the whole point of it, and the web keeps the channel in `?channel=`.
 */
private const val QM_RECONNECT_MS = 3_000L

enum class QueueFilter(val label: String, val mediaType: String?) {
    ALL("🎯 All", null),
    MUSIC("🎵 Music", "MUSIC"),
    VIDEO("🎬 Video", "VIDEO"),
}

data class QueueManagerUiState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    val channel: String = "video",
    val connected: Boolean = false,
    val queue: List<LiveStreamQueueItem> = emptyList(),
    val currentMedia: MediaFile? = null,
    val eligible: List<MediaFile> = emptyList(),
    val publicPlaylists: List<Playlist> = emptyList(),
    val error: String? = null,
) {
    val upNext: List<LiveStreamQueueItem> get() = queue.filter { it.status == "QUEUED" }
}

@HiltViewModel
class QueueManagerViewModel @Inject constructor(
    private val api: LiveStreamApi,
    private val mediaApi: MediaApi,
    private val playlistApi: PlaylistApi,
    private val tokenStore: TokenStore,
    @SseOkHttp private val sseClient: OkHttpClient,
    private val json: Json,
) : ViewModel() {
    private val _state = MutableStateFlow(QueueManagerUiState())
    val state = _state.asStateFlow()

    val userId: Int get() = tokenStore.userId

    private var eventSource: EventSource? = null
    private var reconnectJob: Job? = null
    private var started = false

    fun start(channel: String) {
        if (started && _state.value.channel == channel) return
        started = true
        setChannel(channel)
    }

    fun setChannel(channel: String) {
        if (started && _state.value.channel == channel && !_state.value.loading) return
        _state.value = _state.value.copy(
            channel = channel,
            loading = true,
            error = null,
            // Media and playlists are channel-scoped; clear so the old channel's list can't show.
            eligible = emptyList(),
            publicPlaylists = emptyList(),
            queue = emptyList(),
            currentMedia = null,
        )
        loadEligible()
        loadPublicPlaylists()
        connectStream()
    }

    /**
     * The web unions two sources: the server's eligible list (public media + items from public
     * playlists) plus the signed-in user's own media of the channel's type. Without the second
     * call your own unpublished uploads are unqueueable.
     */
    private fun loadEligible() = viewModelScope.launch {
        val channel = _state.value.channel
        val eligible = safeApiCall { api.eligibleMedia(channel) }.successOrNull?.media.orEmpty()
        val own = safeApiCall { mediaApi.byUser(tokenStore.userId) }.successOrNull.orEmpty()
        val allowedType = if (channel.equals("music", true)) "MUSIC" else "VIDEO"

        val merged = LinkedHashMap<Int, MediaFile>()
        eligible.forEach { merged[it.id] = it }
        own.filter { it.mediaType.equals(allowedType, true) }.forEach { merged.putIfAbsent(it.id, it) }

        _state.value = _state.value.copy(eligible = merged.values.toList())
    }

    private fun loadPublicPlaylists() = viewModelScope.launch {
        val mediaType = if (_state.value.channel.equals("music", true)) "MUSIC" else "VIDEO"
        val playlists = safeApiCall { playlistApi.public(mediaType) }.successOrNull.orEmpty()
        _state.value = _state.value.copy(publicPlaylists = playlists)
    }

    private fun connectStream() {
        eventSource?.cancel()
        reconnectJob?.cancel()
        val channel = _state.value.channel
        val request = Request.Builder()
            .url("${ApiUrls.LEXICON}api/livestream/updates?channel=$channel")
            .build()

        eventSource = EventSources.createFactory(sseClient).newEventSource(request, object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: okhttp3.Response) {
                _state.value = _state.value.copy(connected = true)
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull()
                when (type) {
                    "heartbeat" -> _state.value = _state.value.copy(connected = true)
                    "init" -> {
                        val queue = root?.get("queue")?.let(::decodeQueue).orEmpty()
                        val current = root?.get("state")?.let { element ->
                            runCatching { element.jsonObject["currentMedia"] }.getOrNull()?.let(::decodeMedia)
                        }
                        _state.value = _state.value.copy(
                            loading = false,
                            connected = true,
                            queue = queue,
                            currentMedia = current ?: _state.value.currentMedia,
                        )
                    }
                    "state-update" -> {
                        val payload = root?.get("data") ?: root ?: return
                        val current = runCatching { payload.jsonObject["currentMedia"] }.getOrNull()?.let(::decodeMedia)
                        if (current != null) _state.value = _state.value.copy(currentMedia = current)
                    }
                    "queue-update" -> {
                        val items = (root?.get("data") ?: root)?.let(::decodeQueue) ?: return
                        _state.value = _state.value.copy(queue = items)
                    }
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                _state.value = _state.value.copy(connected = false)
                eventSource.cancel()
                // The web uses a flat 3s retry here, not the stream page's backoff.
                reconnectJob = viewModelScope.launch {
                    delay(QM_RECONNECT_MS)
                    connectStream()
                }
            }
        })
    }

    private fun decodeQueue(data: JsonElement): List<LiveStreamQueueItem>? =
        runCatching { json.decodeFromJsonElement(ListSerializer(LiveStreamQueueItem.serializer()), data) }.getOrNull()

    private fun decodeMedia(data: JsonElement): MediaFile? =
        runCatching { json.decodeFromJsonElement(MediaFile.serializer(), data) }.getOrNull()

    fun add(media: MediaFile) = act { api.add(_state.value.channel, QueueMediaRequest(tokenStore.userId, media.id)) }
    fun addPlaylist(playlist: Playlist) = act { api.addPlaylist(_state.value.channel, QueuePlaylistRequest(tokenStore.userId, playlist.id)) }
    fun remove(item: LiveStreamQueueItem) = act { api.remove(item.id, tokenStore.userId, _state.value.channel) }
    fun skip() = act { api.skip(_state.value.channel, SkipStreamRequest(tokenStore.userId)) }

    fun dismissError() { _state.value = _state.value.copy(error = null) }

    /** No refresh() afterwards — SSE `queue-update` is what repaints, exactly as on the web. */
    private fun act(call: suspend () -> Unit) = viewModelScope.launch {
        _state.value = _state.value.copy(saving = true, error = null)
        when (val result = safeApiCall { call() }) {
            is ApiResult.Success -> _state.value = _state.value.copy(saving = false)
            is ApiResult.Failure -> _state.value = _state.value.copy(saving = false, error = result.message)
            ApiResult.Unauthorized -> _state.value = _state.value.copy(saving = false, error = "Sign in again to manage the queue.")
        }
    }

    override fun onCleared() {
        reconnectJob?.cancel()
        eventSource?.cancel()
        super.onCleared()
    }
}

@Composable
fun QueueManagerScreen(
    channel: String,
    onBack: () -> Unit,
    onOpenStream: (String) -> Unit,
    viewModel: QueueManagerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(QueueFilter.ALL) }
    var sort by remember { mutableStateOf(QueueSort.TITLE) }
    var showPlaylists by remember { mutableStateOf(false) }

    LaunchedEffect(channel) { viewModel.start(channel) }

    val isMusic = state.channel.equals("music", true)
    val typeFiltered = remember(state.eligible, filter) {
        state.eligible.filter { filter.mediaType == null || it.mediaType.equals(filter.mediaType, true) }
    }
    val filtered = remember(typeFiltered, query, sort) { filterAndSort(typeFiltered, query, sort) }

    if (showPlaylists) {
        AddPlaylistDialog(
            isMusic = isMusic,
            playlists = state.publicPlaylists,
            saving = state.saving,
            onAdd = { viewModel.addPlaylist(it); showPlaylists = false },
            onDismiss = { showPlaylists = false },
        )
    }

    ScreenScaffold(
        title = if (isMusic) "🎵 Queue Manager" else "🎬 Queue Manager",
        onBack = onBack,
        background = R.drawable.bg_dashboard,
        actions = {
            Text(
                if (state.connected) "● Connected" else "○ Connecting...",
                style = MaterialTheme.typography.labelSmall,
                color = OnScrimDim,
                modifier = Modifier.padding(end = 12.dp),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ── Channel tabs + stream link ───────────────────────────────────
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = isMusic,
                        onClick = { viewModel.setChannel("music") },
                        label = { Text("🎵 Music") },
                    )
                    FilterChip(
                        selected = !isMusic,
                        onClick = { viewModel.setChannel("video") },
                        label = { Text("🎬 Video") },
                    )
                    Spacer(Modifier.width(4.dp))
                    OutlinedButton(onClick = { onOpenStream(state.channel) }) {
                        Text(if (isMusic) "📡 Music Stream" else "📡 Video Stream")
                    }
                }
            }

            state.error?.let { message ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                            TextButton(onClick = viewModel::dismissError) { Text("✕") }
                        }
                    }
                }
            }

            // ── Panel 1: Live Stream Queue ───────────────────────────────────
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Live Stream Queue",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = OnScrim,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { showPlaylists = true }, modifier = Modifier.weight(1f)) {
                        Text("📋 Add Playlist")
                    }
                    Button(onClick = viewModel::skip, enabled = !state.saving, modifier = Modifier.weight(1f)) {
                        Text("⏭ Vote Skip")
                    }
                }
            }

            state.currentMedia?.let { media ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "Now Playing",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(media.displayTitle, style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (media.mediaType.equals("VIDEO", true)) "🎬 Video" else "🎵 Music",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    "Up Next (${state.upNext.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = OnScrim,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            when {
                state.loading -> item { Text("Loading queue...", style = MaterialTheme.typography.bodySmall, color = OnScrimDim) }
                state.upNext.isEmpty() -> item {
                    Text(
                        "Queue is empty — random media will auto-play",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnScrimDim,
                    )
                }
                else -> itemsIndexed(state.upNext, key = { _, item -> "q-${item.id}" }) { index, item ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${index + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Text(if (item.mediaFile?.mediaType.equals("VIDEO", true)) "🎬" else "🎵")
                            Spacer(Modifier.width(10.dp))
                            Text(
                                item.mediaFile?.displayTitle ?: "Media #${item.mediaFileId}",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (item.addedBy == viewModel.userId) {
                                TextButton(onClick = { viewModel.remove(item) }, enabled = !state.saving) { Text("✕") }
                            }
                        }
                    }
                }
            }

            // ── Panel 2: Browse Media ────────────────────────────────────────
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                    Text(
                        "Browse Media",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = OnScrim,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${state.eligible.size} available",
                        style = MaterialTheme.typography.labelSmall,
                        color = OnScrimDim,
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search by title or description...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    QueueFilter.entries.forEach { option ->
                        FilterChip(
                            selected = filter == option,
                            onClick = { filter = option },
                            label = { Text(option.label, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Sort:", style = MaterialTheme.typography.labelSmall, color = OnScrimDim)
                    QueueSort.entries.forEach { option ->
                        FilterChip(
                            selected = sort == option,
                            onClick = { sort = option },
                            label = { Text(option.label, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }
            item {
                Text(
                    "${filtered.size} of ${state.eligible.size} items",
                    style = MaterialTheme.typography.labelSmall,
                    color = OnScrimDim,
                )
            }

            if (filtered.isEmpty()) {
                item {
                    Text(
                        "No matching media",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnScrimDim,
                    )
                }
            }

            items(filtered, key = { "m-${it.id}" }) { media ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (media.mediaType.equals("VIDEO", true)) "🎬" else "🎵")
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(media.displayTitle, style = MaterialTheme.typography.bodyMedium)
                            if (media.description.isNotBlank()) {
                                Text(
                                    media.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        TextButton(onClick = { viewModel.add(media) }, enabled = !state.saving) { Text("+ Add") }
                    }
                }
            }
        }
    }
}

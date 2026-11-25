package com.alexdyakin.lexicon.ui.lexicon

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import com.alexdyakin.lexicon.ui.components.ActionRow
import com.alexdyakin.lexicon.ui.components.LexButton
import com.alexdyakin.lexicon.ui.components.LexChip
import com.alexdyakin.lexicon.ui.components.LexIconButton
import com.alexdyakin.lexicon.ui.components.LexTone
import com.alexdyakin.lexicon.ui.components.ListRow
import com.alexdyakin.lexicon.ui.components.ScreenScaffold
import com.alexdyakin.lexicon.ui.components.SectionHeader
import com.alexdyakin.lexicon.ui.components.SplitActions
import com.alexdyakin.lexicon.ui.theme.Elevation
import com.alexdyakin.lexicon.ui.theme.Radii
import com.alexdyakin.lexicon.ui.theme.Sizes
import com.alexdyakin.lexicon.ui.theme.Spacing
import com.alexdyakin.lexicon.ui.theme.Status
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
/** Ceiling for the SSE reconnect backoff, so an offline phone stops hammering. */
private const val QM_RECONNECT_MAX_MS = 60_000L

enum class QueueFilter(val label: String, val mediaType: String?) {
    ALL("All", null),
    MUSIC("Music", "MUSIC"),
    VIDEO("Video", "VIDEO"),
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
    private var reconnectDelayMs = QM_RECONNECT_MS
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
                reconnectDelayMs = QM_RECONNECT_MS
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
                // The web client retries flat every 3s. On a phone that is a different
                // proposition: offline, it meant a connection attempt every three seconds
                // forever, and nothing stopped it when the app went to the background. Back
                // off to a ceiling instead, and reset the delay once a connection succeeds.
                reconnectJob = viewModelScope.launch {
                    delay(reconnectDelayMs)
                    reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(QM_RECONNECT_MAX_MS)
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
        title = "Queue Manager",
        onBack = onBack,
        gutter = Spacing.gutter,
        // Was the text "● Connected" / "○ Connecting..." in the actions slot — two Unicode
        // bullets doing the work of a status light, in a slot the title has to share.
        actions = {
            Icon(
                if (state.connected) Icons.Filled.CloudDone else Icons.Filled.CloudOff,
                contentDescription = if (state.connected) "Connected" else "Connecting",
                tint = if (state.connected) Status.ok else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = Spacing.md).size(Sizes.icon),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(vertical = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            // ── Channel tabs + stream link ───────────────────────────────────
            // Two chips and a button, each carrying an emoji, came to about 372dp of fixed
            // children in the 328dp a 360dp phone has. A FlowRow wraps the stream button under
            // the chips instead of clipping it.
            item {
                ActionRow {
                    LexChip(
                        label = "Music",
                        selected = isMusic,
                        onClick = { viewModel.setChannel("music") },
                        leadingIcon = Icons.Filled.MusicNote,
                    )
                    LexChip(
                        label = "Video",
                        selected = !isMusic,
                        onClick = { viewModel.setChannel("video") },
                        leadingIcon = Icons.Filled.Movie,
                    )
                    LexButton(
                        text = if (isMusic) "Music stream" else "Video stream",
                        onClick = { onOpenStream(state.channel) },
                        icon = Icons.Filled.Podcasts,
                    )
                }
            }

            state.error?.let { message ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                        shape = Radii.card,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        ListRow(
                            modifier = Modifier.padding(
                                start = Spacing.md,
                                end = Spacing.sm,
                                top = Spacing.sm,
                                bottom = Spacing.sm,
                            ),
                            actionSlotWidth = Sizes.touch,
                            action = {
                                LexIconButton(
                                    icon = Icons.Filled.Close,
                                    contentDescription = "Dismiss error",
                                    onClick = viewModel::dismissError,
                                )
                            },
                        ) {
                            Text(
                                message,
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }

            // ── Panel 1: Live Stream Queue ───────────────────────────────────
            item { SectionHeader("Live Stream Queue") }
            item {
                SplitActions(
                    secondary = {
                        LexButton(
                            text = "Add playlist",
                            onClick = { showPlaylists = true },
                            icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    primary = {
                        LexButton(
                            text = "Vote skip",
                            onClick = viewModel::skip,
                            tone = LexTone.Primary,
                            icon = Icons.Filled.SkipNext,
                            enabled = !state.saving,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                )
            }

            state.currentMedia?.let { media ->
                item {
                    Card(
                        shape = Radii.card,
                        elevation = CardDefaults.cardElevation(defaultElevation = Elevation.card),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        ListRow(
                            modifier = Modifier.padding(Spacing.md),
                            actionSlotWidth = Sizes.icon,
                            action = {
                                Icon(
                                    kindIcon(media.mediaType),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(Sizes.icon),
                                )
                            },
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Now playing",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    media.displayTitle,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            item { SectionHeader("Up next (${state.upNext.size})") }

            when {
                state.loading -> item { Hint("Loading queue...") }
                state.upNext.isEmpty() ->
                    item { Hint("Queue is empty — random media will auto-play") }
                else -> itemsIndexed(state.upNext, key = { _, item -> "q-${item.id}" }) { index, item ->
                    QueueItemRow(
                        position = index + 1,
                        title = item.mediaFile?.displayTitle ?: "Media #${item.mediaFileId}",
                        mediaType = item.mediaFile?.mediaType,
                        removable = item.addedBy == viewModel.userId,
                        saving = state.saving,
                        onRemove = { viewModel.remove(item) },
                    )
                }
            }

            // ── Panel 2: Browse Media ────────────────────────────────────────
            item {
                SectionHeader("Browse media") {
                    Text(
                        "${state.eligible.size} available",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search by title or description", maxLines = 1) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Column {
                    FieldLabel("Filter")
                    ActionRow {
                        QueueFilter.entries.forEach { option ->
                            LexChip(
                                label = option.label,
                                selected = filter == option,
                                onClick = { filter = option },
                            )
                        }
                    }
                }
            }
            item {
                // "Sort:" plus every sort chip in one Row needed about 316dp of the 328dp
                // available, so the last chip clipped at 360dp and two of them did at 320dp.
                Column {
                    FieldLabel("Sort")
                    ActionRow {
                        QueueSort.entries.forEach { option ->
                            LexChip(
                                label = option.label,
                                selected = sort == option,
                                onClick = { sort = option },
                            )
                        }
                    }
                }
            }
            item { Hint("${filtered.size} of ${state.eligible.size} items") }

            if (filtered.isEmpty()) {
                item { Hint("No matching media") }
            }

            items(filtered, key = { "m-${it.id}" }) { media ->
                Card(
                    shape = Radii.card,
                    elevation = CardDefaults.cardElevation(defaultElevation = Elevation.card),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ListRow(
                        modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                        action = {
                            LexButton(
                                text = "Add",
                                onClick = { viewModel.add(media) },
                                icon = Icons.Filled.Add,
                                enabled = !state.saving,
                            )
                        },
                    ) {
                        Icon(
                            kindIcon(media.mediaType),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(Sizes.icon),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                media.displayTitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (media.description.isNotBlank()) {
                                Text(
                                    media.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The label above a group of chips, so "Filter" and "Sort" read the same way. */
@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = Spacing.xs),
    )
}

/** A line of secondary copy — counts, empty states, loading notes. */
@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

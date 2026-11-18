package com.alexdyakin.lexicon.ui.lexicon

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexdyakin.lexicon.data.ApiResult
import com.alexdyakin.lexicon.data.ApiUrls
import com.alexdyakin.lexicon.data.LiveStreamQueueItem
import com.alexdyakin.lexicon.data.liveStreamServerPosition
import com.alexdyakin.lexicon.data.liveStreamStartPosition
import com.alexdyakin.lexicon.data.LiveStreamState
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
import com.alexdyakin.lexicon.media.PlayerConnection
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
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

/**
 * Live stream, ported from `Lexicon/src/pages/MusicLiveStream.js` and `VideoLiveStream.js`.
 *
 * Those two files are ~95% identical, so this is one screen parameterised by channel — but each
 * instance is FIXED to its channel, exactly like the web routes `/music-stream` and
 * `/video-stream`. There is deliberately no channel switcher here; switching channels is the
 * Queue Manager's job.
 *
 * The web `.stream-controls` row has exactly four buttons — Skip, Add Playlist, Add to Queue,
 * Manage Queue. Do not add more.
 */

/**
 * Longer than any plausible single track. A computed live position beyond this means the
 * server's start time is stale, not that we joined very late.
 */

enum class StreamConnection { CONNECTING, CONNECTED, DISCONNECTED }

enum class QueueSort(val label: String) {
    TITLE("A–Z"),
    NEWEST("Newest"),
    OLDEST("Oldest"),
}

data class LiveStreamUiState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    val channel: String = "music",
    val connection: StreamConnection = StreamConnection.CONNECTING,
    val lastSyncTime: Long = 0L,
    val state: LiveStreamState = LiveStreamState(),
    val queue: List<LiveStreamQueueItem> = emptyList(),
    val eligible: List<MediaFile> = emptyList(),
    val publicPlaylists: List<Playlist> = emptyList(),
    val error: String? = null,
) {
    /** The web filters the queue to QUEUED everywhere it renders "Up Next". */
    val upNext: List<LiveStreamQueueItem> get() = queue.filter { it.status == "QUEUED" }
}

@HiltViewModel
class LiveStreamViewModel @Inject constructor(
    private val api: LiveStreamApi,
    private val mediaApi: MediaApi,
    private val playlistApi: PlaylistApi,
    private val tokenStore: TokenStore,
    val player: PlayerConnection,
    @SseOkHttp private val sseClient: OkHttpClient,
    private val json: Json,
) : ViewModel() {
    private val _state = MutableStateFlow(LiveStreamUiState())
    val state = _state.asStateFlow()

    val userId: Int get() = tokenStore.userId

    private var eventSource: EventSource? = null
    private var syncJob: Job? = null
    private var reconnectJob: Job? = null
    private var retryCount = 0
    private var lastHeartbeat = 0L

    /** Guards the double-fire the web guards with `mediaEndedInProgressRef` (2s window). */
    private var mediaEndedAt = 0L

    /** Matches `hasSyncedRef` — the first sync after a track change seeks, later ones only correct drift. */
    private var hasSynced = false
    private var lastLoadedMediaId = -1

    init {
        viewModelScope.launch {
            player.trackEnded.collect { reportMediaEnded() }
        }
    }

    /** Called once from the screen; the channel comes from the route, then never changes. */
    fun start(channel: String) {
        if (_state.value.channel == channel && !_state.value.loading) return
        _state.value = _state.value.copy(channel = channel)
        refresh()
        connectStream()
        startDriftWatch()
    }

    // ── Server state ─────────────────────────────────────────────────────────

    fun refresh() = viewModelScope.launch {
        val channel = _state.value.channel
        val stream = safeApiCall { api.state(channel) }.successOrNull?.state ?: LiveStreamState(channel = channel)
        val queue = safeApiCall { api.queue(channel) }.successOrNull?.queue.orEmpty()
        _state.value = _state.value.copy(loading = false, state = stream, queue = queue)
        ensureCurrentMediaLoaded(stream)
        // The web starts playing off this REST response too — its `currentMedia` effect fires
        // regardless of whether the value came from fetch or from SSE. Don't wait for `init`.
        applyStreamToPlayer(stream)
    }

    /** The web defers this until the "Add to Queue" modal opens. */
    fun loadEligibleMedia() = viewModelScope.launch {
        if (_state.value.eligible.isNotEmpty()) return@launch
        val media = safeApiCall { api.eligibleMedia(_state.value.channel) }.successOrNull?.media.orEmpty()
        _state.value = _state.value.copy(eligible = media)
    }

    /**
     * PUBLIC playlists, not the signed-in user's own — `/api/playlists/public?mediaType=…`.
     * A live stream is shared, so the web only offers playlists everyone can see.
     */
    fun loadPublicPlaylists() = viewModelScope.launch {
        if (_state.value.publicPlaylists.isNotEmpty()) return@launch
        val mediaType = if (_state.value.channel.equals("video", true)) "VIDEO" else "MUSIC"
        val playlists = safeApiCall { playlistApi.public(mediaType) }.successOrNull.orEmpty()
        _state.value = _state.value.copy(publicPlaylists = playlists)
    }

    private fun ensureCurrentMediaLoaded(stream: LiveStreamState) {
        if (stream.currentMedia != null || stream.currentMediaId <= 0) return
        viewModelScope.launch {
            val media = safeApiCall { mediaApi.byId(stream.currentMediaId) }.successOrNull ?: return@launch
            val current = _state.value.state
            if (current.currentMediaId == media.id && current.currentMedia == null) {
                val filled = current.copy(currentMedia = media)
                _state.value = _state.value.copy(state = filled)
                // The earlier applyStreamToPlayer saw a null currentMedia and bailed; now that
                // we have it, start playback rather than waiting for the next SSE event.
                applyStreamToPlayer(filled)
            }
        }
    }

    // ── SSE ──────────────────────────────────────────────────────────────────

    private fun connectStream() {
        eventSource?.cancel()
        _state.value = _state.value.copy(connection = StreamConnection.CONNECTING)
        val channel = _state.value.channel
        val request = Request.Builder()
            .url("${ApiUrls.LEXICON}api/livestream/updates?channel=$channel")
            .build()

        eventSource = EventSources.createFactory(sseClient).newEventSource(request, object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: okhttp3.Response) {
                retryCount = 0
                lastHeartbeat = System.currentTimeMillis()
                _state.value = _state.value.copy(connection = StreamConnection.CONNECTED)
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull()
                when (type) {
                    "heartbeat" -> {
                        lastHeartbeat = System.currentTimeMillis()
                        retryCount = 0
                        _state.value = _state.value.copy(connection = StreamConnection.CONNECTED)
                    }
                    "init" -> {
                        val stream = root?.get("state")?.let(::decodeState) ?: return
                        val queue = root["queue"]?.let(::decodeQueue).orEmpty()
                        _state.value = _state.value.copy(
                            loading = false,
                            state = stream,
                            queue = queue,
                            connection = StreamConnection.CONNECTED,
                            lastSyncTime = System.currentTimeMillis(),
                        )
                        retryCount = 0
                        ensureCurrentMediaLoaded(stream)
                        applyStreamToPlayer(stream)
                    }
                    "state-update" -> {
                        val stream = root?.get("data")?.let(::decodeState) ?: return
                        _state.value = _state.value.copy(state = stream, lastSyncTime = System.currentTimeMillis())
                        ensureCurrentMediaLoaded(stream)
                        applyStreamToPlayer(stream)
                    }
                    "queue-update" -> {
                        val queue = root?.get("data")?.let(::decodeQueue) ?: return
                        _state.value = _state.value.copy(queue = queue, lastSyncTime = System.currentTimeMillis())
                    }
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                _state.value = _state.value.copy(connection = StreamConnection.DISCONNECTED)
                eventSource.cancel()
                scheduleReconnect()
            }
        })
    }

    /** Web backoff: `min(1000 * 2^min(retry,5), 30000)` — and it never gives up. */
    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            val delayMs = min(1000.0 * 2.0.pow(min(retryCount, 5)), 30_000.0).toLong()
            retryCount++
            delay(delayMs)
            connectStream()
        }
    }

    private fun decodeState(data: JsonElement): LiveStreamState? =
        runCatching { json.decodeFromJsonElement(LiveStreamState.serializer(), data) }.getOrNull()

    private fun decodeQueue(data: JsonElement): List<LiveStreamQueueItem>? =
        runCatching { json.decodeFromJsonElement(ListSerializer(LiveStreamQueueItem.serializer()), data) }.getOrNull()

    // ── Playback sync ────────────────────────────────────────────────────────

    /**
     * Thresholds are the web's, not invented ones:
     *  - a late joiner more than 15s in seeks to the live position, otherwise starts at 0
     *  - afterwards drift is only corrected past 30s, checked every 60s
     * Tighter correction sounds worse, not better — it re-seeks audibly over normal jitter.
     */
    private fun applyStreamToPlayer(stream: LiveStreamState) {
        val media = stream.currentMedia ?: return
        if (media.id == lastLoadedMediaId) return
        lastLoadedMediaId = media.id
        hasSynced = false

        val startAt = liveStreamStartPosition(stream)
        player.play(listOf(media), 0, startAt)
        hasSynced = true
    }

    private fun startDriftWatch() {
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            while (true) {
                delay(60_000)
                val stream = _state.value.state
                val media = stream.currentMedia ?: continue
                val local = player.state.value
                if (local.mediaId != media.id.toString()) continue
                if (abs(local.positionMs - liveStreamServerPosition(stream)) > 30_000L) {
                    player.seekTo(liveStreamServerPosition(stream))
                }

                // Client-side stale detector: the web reconnects if no heartbeat in 90s.
                if (lastHeartbeat > 0 && System.currentTimeMillis() - lastHeartbeat > 90_000) {
                    retryCount = 0
                    lastHeartbeat = System.currentTimeMillis()
                    connectStream()
                }
            }
        }
    }

    private fun reportMediaEnded() {
        val now = System.currentTimeMillis()
        if (now - mediaEndedAt < 2_000) return
        mediaEndedAt = now
        viewModelScope.launch {
            val result = safeApiCall { api.mediaEnded(_state.value.channel) }
            if (result !is ApiResult.Success) refresh()
        }
    }

    // ── Actions — the four the web exposes ───────────────────────────────────

    fun skip() = act { api.skip(_state.value.channel, SkipStreamRequest(tokenStore.userId)) }
    fun add(media: MediaFile) = act { api.add(_state.value.channel, QueueMediaRequest(tokenStore.userId, media.id)) }
    fun addPlaylist(playlist: Playlist) = act { api.addPlaylist(_state.value.channel, QueuePlaylistRequest(tokenStore.userId, playlist.id)) }
    fun remove(item: LiveStreamQueueItem) = act { api.remove(item.id, tokenStore.userId, _state.value.channel) }

    fun dismissError() { _state.value = _state.value.copy(error = null) }

    private fun act(call: suspend () -> Unit) = viewModelScope.launch {
        _state.value = _state.value.copy(saving = true, error = null)
        when (val result = safeApiCall { call() }) {
            is ApiResult.Success -> {
                _state.value = _state.value.copy(saving = false)
                refresh()
            }
            is ApiResult.Failure -> _state.value = _state.value.copy(saving = false, error = result.message)
            ApiResult.Unauthorized -> _state.value = _state.value.copy(saving = false, error = "Sign in again to manage the queue.")
        }
    }

    override fun onCleared() {
        syncJob?.cancel()
        reconnectJob?.cancel()
        eventSource?.cancel()
        super.onCleared()
    }
}

@Composable
fun LiveStreamScreen(
    channel: String,
    onBack: () -> Unit,
    onOpenQueueManager: (String) -> Unit,
    viewModel: LiveStreamViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var showAddMedia by remember { mutableStateOf(false) }
    var showPlaylists by remember { mutableStateOf(false) }

    LaunchedEffect(channel) { viewModel.start(channel) }

    val isMusic = !channel.equals("video", true)

    if (showAddMedia) {
        AddToQueueDialog(
            isMusic = isMusic,
            media = state.eligible,
            saving = state.saving,
            onAdd = { viewModel.add(it) },
            onDismiss = { showAddMedia = false },
        )
    }
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
        title = if (isMusic) "Music live stream" else "Video live stream",
        onBack = onBack,
        gutter = Spacing.gutter,
        actions = { ConnectionIndicator(state.connection) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(vertical = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
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

            if (state.connection == StreamConnection.DISCONNECTED && state.error == null) {
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(Sizes.iconSm),
                        )
                        Text(
                            "Connection lost. Attempting to reconnect...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            // ── Now playing ──────────────────────────────────────────────────
            item {
                Card(
                    shape = Radii.card,
                    elevation = CardDefaults.cardElevation(defaultElevation = Elevation.card),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.padding(Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LiveBadge()
                            Text(
                                "Now playing",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        val media = state.state.currentMedia
                        if (media != null) {
                            Text(
                                media.displayTitle,
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                media.description.ifBlank { "No description" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        } else {
                            Text(
                                if (isMusic) {
                                    "No music currently playing"
                                } else {
                                    "No video currently playing"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                "Add something to the queue to start.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // ── The four stream controls ─────────────────────────────────────
            // Two equal-weight pairs rather than four content-sized buttons: every label is a
            // different length, and unweighted children are measured in declaration order, so
            // the last of four is always the one that gets starved.
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    SplitActions(
                        secondary = {
                            LexButton(
                                text = "Add playlist",
                                onClick = {
                                    showPlaylists = true
                                    viewModel.loadPublicPlaylists()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                        primary = {
                            LexButton(
                                text = "Skip",
                                onClick = viewModel::skip,
                                tone = LexTone.Primary,
                                icon = Icons.Filled.SkipNext,
                                enabled = !state.saving,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                    )
                    SplitActions(
                        secondary = {
                            LexButton(
                                text = "Add to queue",
                                onClick = {
                                    showAddMedia = true
                                    viewModel.loadEligibleMedia()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                        primary = {
                            LexButton(
                                text = "Manage queue",
                                onClick = { onOpenQueueManager(channel) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                    )
                }
            }

            // ── Up next ─────────────────────────────────────────────────────
            item { SectionHeader("Up next (${state.upNext.size})") }

            if (state.upNext.isEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Text(
                            "Queue is empty",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            if (isMusic) {
                                "Random music will play when the queue is empty"
                            } else {
                                "Random video will play when the queue is empty"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            itemsIndexed(state.upNext, key = { _, item -> item.id }) { index, item ->
                QueueItemRow(
                    position = index + 1,
                    title = item.mediaFile?.displayTitle ?: "Media #${item.mediaFileId}",
                    subtitle = "Added by user #${item.addedBy}",
                    mediaType = item.mediaFile?.mediaType,
                    // The web only lets you remove what you queued yourself.
                    removable = item.addedBy == viewModel.userId,
                    saving = state.saving,
                    onRemove = { viewModel.remove(item) },
                )
            }
        }
    }
}

/**
 * The live dot.
 *
 * Was the string `"● LIVE"` in bold error red. The bullet is a glyph with no tint of its own and a
 * width that varies by device font, so it drifted away from its label; this is a real badge.
 */
@Composable
private fun LiveBadge() {
    Surface(color = MaterialTheme.colorScheme.error, shape = Radii.pill) {
        Text(
            "LIVE",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onError,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
        )
    }
}

/**
 * Connection state as one icon, replacing the `🟢 / 🟡 / 🔴` emoji pill.
 *
 * Emoji are the one thing in a theme that cannot be recoloured, so the old pill stayed bright
 * green-yellow-red whatever the palette did, and its three variants were three different widths
 * in a slot the screen title has to share.
 */
@Composable
private fun ConnectionIndicator(connection: StreamConnection) {
    val (icon, tint, label) = when (connection) {
        StreamConnection.CONNECTED ->
            Triple(Icons.Filled.CloudDone, Status.ok, "Connected")
        StreamConnection.CONNECTING ->
            Triple(Icons.Filled.CloudQueue, Status.warn, "Connecting")
        StreamConnection.DISCONNECTED ->
            Triple(Icons.Filled.CloudOff, Status.danger, "Disconnected")
    }
    Icon(
        icon,
        contentDescription = label,
        tint = tint,
        modifier = Modifier.padding(end = Spacing.md).size(Sizes.icon),
    )
}

/**
 * One queued track, shared by the stream screen and the Queue Manager.
 *
 * Both screens had their own copy, and both put the remove control in as the character `✕` inside
 * a `TextButton` — no tint, no content description, and a width that moved with the device font.
 * The two also disagreed on padding (14dp against 12dp) and on the type role for the position
 * number, which was visible when you navigated between them.
 */
@Composable
internal fun QueueItemRow(
    position: Int,
    title: String,
    mediaType: String?,
    removable: Boolean,
    saving: Boolean,
    onRemove: () -> Unit,
    subtitle: String? = null,
) {
    Card(
        shape = Radii.card,
        elevation = CardDefaults.cardElevation(defaultElevation = Elevation.card),
        modifier = Modifier.fillMaxWidth(),
    ) {
        ListRow(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            actionSlotWidth = Sizes.touch,
            action = {
                if (removable) {
                    LexIconButton(
                        icon = Icons.Filled.Close,
                        contentDescription = "Remove from queue",
                        onClick = onRemove,
                        enabled = !saving,
                        tone = LexTone.Destructive,
                    )
                }
            },
        ) {
            Text(
                "$position",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Icon(
                kindIcon(mediaType),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Sizes.iconSm),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
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

/** Search + sort + "N of M tracks", as in the web `.add-queue-modal`. */
@Composable
private fun AddToQueueDialog(
    isMusic: Boolean,
    media: List<MediaFile>,
    saving: Boolean,
    onAdd: (MediaFile) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(QueueSort.TITLE) }

    val filtered = remember(media, query, sort) { filterAndSort(media, query, sort) }
    val noun = if (isMusic) "tracks" else "videos"

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = Radii.card,
        title = { Text(if (isMusic) "Add music to queue" else "Add video to queue") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search by title or description", maxLines = 1) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                ActionRow {
                    QueueSort.entries.forEach { option ->
                        LexChip(
                            label = option.label,
                            selected = sort == option,
                            onClick = { sort = option },
                        )
                    }
                }
                Text(
                    "${filtered.size} of ${media.size} $noun",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (filtered.isEmpty()) {
                    Text(
                        if (query.isBlank()) {
                            "No media found"
                        } else {
                            "No media found matching \"$query\""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // Bounded, so the list cannot push the dialog's own buttons off a short
                    // screen or out of reach at a large font scale.
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(filtered, key = { it.id }) { file ->
                            ListRow(
                                action = {
                                    LexButton(
                                        text = "Add",
                                        onClick = { onAdd(file) },
                                        icon = Icons.Filled.Add,
                                        enabled = !saving,
                                    )
                                },
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        file.displayTitle,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (file.description.isNotBlank()) {
                                        Text(
                                            file.description,
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
        },
        confirmButton = { LexButton("Close", onDismiss, tone = LexTone.Primary) },
    )
}

/** Shared with the Queue Manager, which offers the same public-playlist picker. */
@Composable
internal fun AddPlaylistDialog(
    isMusic: Boolean,
    playlists: List<Playlist>,
    saving: Boolean,
    onAdd: (Playlist) -> Unit,
    onDismiss: () -> Unit,
) = AlertDialog(
    onDismissRequest = onDismiss,
    shape = Radii.card,
    title = { Text("Add playlist to queue") },
    text = {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            val kind = if (isMusic) "music" else "video"
            Text(
                "${playlists.size} public $kind playlist${if (playlists.size == 1) "" else "s"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (playlists.isEmpty()) {
                Text(
                    "No public $kind playlists found",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(playlists, key = { it.id }) { playlist ->
                        ListRow(
                            action = {
                                LexButton(
                                    text = "Queue all",
                                    onClick = { onAdd(playlist) },
                                    icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                                    enabled = !saving,
                                )
                            },
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    playlist.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${playlist.itemCount} tracks",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    },
    confirmButton = { LexButton("Close", onDismiss, tone = LexTone.Primary) },
)

/** Shared by the stream modal and the Queue Manager — same rules as the web's `filteredMedia`. */
internal fun filterAndSort(media: List<MediaFile>, query: String, sort: QueueSort): List<MediaFile> {
    val q = query.trim().lowercase()
    val matched = if (q.isBlank()) media else media.filter {
        it.title.lowercase().contains(q) ||
            it.originalFilename.lowercase().contains(q) ||
            it.description.lowercase().contains(q)
    }
    return when (sort) {
        QueueSort.TITLE -> matched.sortedBy { it.displayTitle.lowercase() }
        QueueSort.NEWEST -> matched.sortedByDescending { it.id }
        QueueSort.OLDEST -> matched.sortedBy { it.id }
    }
}

/** Replaces the `🎬` / `🎵` glyphs the queue rows used in five places. */
internal fun kindIcon(mediaType: String?): ImageVector =
    if (mediaType.equals("VIDEO", true)) Icons.Filled.Movie else Icons.Filled.MusicNote

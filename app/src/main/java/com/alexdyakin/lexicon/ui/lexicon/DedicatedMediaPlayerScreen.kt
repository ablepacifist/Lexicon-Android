package com.alexdyakin.lexicon.ui.lexicon

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexdyakin.lexicon.R
import com.alexdyakin.lexicon.data.ApiResult
import com.alexdyakin.lexicon.data.ApiUrls
import com.alexdyakin.lexicon.data.MediaFile
import com.alexdyakin.lexicon.data.MediaKind
import com.alexdyakin.lexicon.data.MediaUpdateRequest
import com.alexdyakin.lexicon.data.Playlist
import com.alexdyakin.lexicon.data.TokenStore
import com.alexdyakin.lexicon.data.api.MediaApi
import com.alexdyakin.lexicon.data.api.PlaybackApi
import com.alexdyakin.lexicon.data.api.PlaylistApi
import com.alexdyakin.lexicon.data.safeApiCall
import com.alexdyakin.lexicon.media.PlayerConnection
import com.alexdyakin.lexicon.ui.components.EmptyBox
import com.alexdyakin.lexicon.ui.components.LoadingBox
import com.alexdyakin.lexicon.ui.components.ActionRow
import com.alexdyakin.lexicon.ui.components.LabeledSwitch
import com.alexdyakin.lexicon.ui.components.LexButton
import com.alexdyakin.lexicon.ui.components.LexChip
import com.alexdyakin.lexicon.ui.components.LexIconButton
import com.alexdyakin.lexicon.ui.components.LexTone
import com.alexdyakin.lexicon.ui.components.PlayPauseButton
import com.alexdyakin.lexicon.ui.components.TransportBar
import com.alexdyakin.lexicon.ui.components.TransportButton
import com.alexdyakin.lexicon.ui.components.ScreenScaffold
import com.alexdyakin.lexicon.ui.theme.Sizes
import com.alexdyakin.lexicon.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.random.Random

enum class DedicatedMediaMode(val title: String, val mediaTypeHint: String, val browserTitle: String, val searchNoun: String) {
    AUDIO("Audio Player", "MUSIC", "Music Browser", "music"),
    VIDEO("Video Player", "VIDEO", "Video Browser", "videos"),
    AUDIOBOOK("Audiobooks", "AUDIOBOOK", "Audiobook Browser", "audiobooks"),
}

enum class PlayerPageTab { LIBRARY, PLAYLISTS }
enum class LibraryVisibility { ALL, PERSONAL, PUBLIC }

data class DedicatedPlayerUiState(
    val loading: Boolean = true,
    val mode: DedicatedMediaMode = DedicatedMediaMode.AUDIO,
    val tab: PlayerPageTab = PlayerPageTab.LIBRARY,
    val library: List<MediaFile> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val selectedPlaylist: Playlist? = null,
    val playlistMode: Boolean = false,
    val libraryVisibility: LibraryVisibility = LibraryVisibility.ALL,
    val query: String = "",
    val playlistQuery: String = "",
    val error: String? = null,
)

@HiltViewModel
class DedicatedMediaPlayerViewModel @Inject constructor(
    private val mediaApi: MediaApi,
    private val playlistApi: PlaylistApi,
    private val playbackApi: PlaybackApi,
    private val tokenStore: TokenStore,
    val player: PlayerConnection,
) : ViewModel() {
    private val _state = MutableStateFlow(DedicatedPlayerUiState())
    val state = _state.asStateFlow()
    private var initializedMode: DedicatedMediaMode? = null
    private var pendingMediaId: Int? = null
    private var pendingPlaylistId: Int? = null

    fun initialize(mode: DedicatedMediaMode, initialMediaId: Int? = null, initialPlaylistId: Int? = null) {
        pendingMediaId = initialMediaId
        pendingPlaylistId = initialPlaylistId
        if (initializedMode == mode && !_state.value.loading && initialMediaId == null && initialPlaylistId == null) return
        initializedMode = mode
        _state.value = _state.value.copy(mode = mode, tab = if (initialPlaylistId != null) PlayerPageTab.PLAYLISTS else PlayerPageTab.LIBRARY)
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        val mode = _state.value.mode
        _state.value = _state.value.copy(loading = true, error = null)

        val mediaResult = loadLibrary(mode)
        val playlistsResult = loadPlaylists(mode)

        _state.value = _state.value.copy(
            loading = false,
            library = mediaResult.first,
            playlists = playlistsResult.first,
            error = mediaResult.second ?: playlistsResult.second,
        )

        pendingPlaylistId?.let {
            loadPlaylistInternal(it, autoPlay = true)
            pendingPlaylistId = null
        }
        pendingMediaId?.let {
            val target = _state.value.library.firstOrNull { media -> media.id == it }
            if (target != null) playMedia(target)
            pendingMediaId = null
        }
    }

    private suspend fun loadLibrary(mode: DedicatedMediaMode): Pair<List<MediaFile>, String?> {
        val mine = safeApiCall { mediaApi.byUser(tokenStore.userId) }
        val pub = safeApiCall { mediaApi.public() }
        val merged = (mine.successOrNull.orEmpty() + pub.successOrNull.orEmpty())
            .associateBy { it.id }
            .values
            .toList()
            .filter { item ->
                when (mode) {
                    DedicatedMediaMode.AUDIO -> item.kind == MediaKind.AUDIO
                    DedicatedMediaMode.VIDEO -> item.kind == MediaKind.VIDEO
                    DedicatedMediaMode.AUDIOBOOK -> item.kind == MediaKind.AUDIOBOOK
                }
            }
            .sortedByDescending { it.uploadDate }
        val error = (mine as? ApiResult.Failure)?.message ?: (pub as? ApiResult.Failure)?.message
        return merged to error
    }

    private suspend fun loadPlaylists(mode: DedicatedMediaMode): Pair<List<Playlist>, String?> {
        val mine = safeApiCall { playlistApi.byUser(tokenStore.userId) }
        val pub = safeApiCall { playlistApi.public() }
        val all = (mine.successOrNull.orEmpty() + pub.successOrNull.orEmpty())
            .associateBy { it.id }
            .values
            .toList()
            .filter { playlist ->
                when (mode) {
                    DedicatedMediaMode.AUDIO -> playlist.mediaType.equals("MUSIC", true) || playlist.mediaType.equals("AUDIO", true)
                    DedicatedMediaMode.VIDEO -> playlist.mediaType.equals("VIDEO", true)
                    DedicatedMediaMode.AUDIOBOOK -> playlist.mediaType.equals("AUDIOBOOK", true)
                }
            }
            .sortedBy { it.name.lowercase() }
        val error = (mine as? ApiResult.Failure)?.message ?: (pub as? ApiResult.Failure)?.message
        return all to error
    }

    fun setTab(tab: Int) {
        _state.value = _state.value.copy(tab = if (tab == 0) PlayerPageTab.LIBRARY else PlayerPageTab.PLAYLISTS)
    }

    fun setQuery(query: String) {
        _state.value = _state.value.copy(query = query)
    }

    fun setLibraryVisibility(visibility: LibraryVisibility) {
        _state.value = _state.value.copy(libraryVisibility = visibility)
    }

    fun setPlaylistQuery(query: String) {
        _state.value = _state.value.copy(playlistQuery = query)
    }

    fun loadPlaylist(playlistId: Int) = viewModelScope.launch { loadPlaylistInternal(playlistId, autoPlay = true) }

    fun exitPlaylistMode() {
        _state.value = _state.value.copy(playlistMode = false, selectedPlaylist = null, tab = PlayerPageTab.LIBRARY)
        refresh()
    }

    private suspend fun loadPlaylistInternal(playlistId: Int, autoPlay: Boolean) {
        val fresh = safeApiCall { playlistApi.byId(playlistId) }.successOrNull
        if (fresh == null) {
            _state.value = _state.value.copy(error = "Unable to load playlist details.")
            return
        }
        _state.value = _state.value.copy(selectedPlaylist = fresh, playlistMode = true, tab = PlayerPageTab.LIBRARY)
        if (autoPlay) playPlaylist(fresh)
    }

    fun updateMedia(mediaId: Int, title: String, description: String, isPublic: Boolean) = viewModelScope.launch {
        when (val result = safeApiCall {
            mediaApi.update(mediaId, tokenStore.userId, MediaUpdateRequest(title = title.trim(), description = description.trim(), isPublic = isPublic))
        }) {
            is ApiResult.Success -> refresh()
            is ApiResult.Failure -> _state.value = _state.value.copy(error = result.message)
            ApiResult.Unauthorized -> _state.value = _state.value.copy(error = "Only the uploader can edit this media file.")
        }
    }

    fun playMedia(media: MediaFile) = viewModelScope.launch {
        val queue = visibleLibrary().filter { it.isPlayable }
        val index = queue.indexOfFirst { it.id == media.id }
        if (index < 0) return@launch
        val resumeMs = if (_state.value.mode == DedicatedMediaMode.AUDIOBOOK) loadResumeMs(media.id) else 0L
        player.play(queue, index, resumeMs)
    }

    fun playPlaylist(playlist: Playlist) = viewModelScope.launch {
        val fresh = safeApiCall { playlistApi.byId(playlist.id) }.successOrNull ?: playlist
        val queue = fresh.items.map { it.mediaFile }.filter { it.isPlayable }
        if (queue.isEmpty()) {
            _state.value = _state.value.copy(error = "Playlist has no playable items.")
            return@launch
        }
        player.setShuffle(false)
        val resumeMs = if (_state.value.mode == DedicatedMediaMode.AUDIOBOOK) loadResumeMs(queue.first().id) else 0L
        player.play(queue, 0, resumeMs)
        _state.value = _state.value.copy(selectedPlaylist = fresh)
    }

    fun playPlaylistShuffled(playlist: Playlist) = viewModelScope.launch {
        val fresh = safeApiCall { playlistApi.byId(playlist.id) }.successOrNull ?: playlist
        val queue = fresh.items.map { it.mediaFile }.filter { it.isPlayable }
        if (queue.isEmpty()) {
            _state.value = _state.value.copy(error = "Playlist has no playable items.")
            return@launch
        }
        val startIndex = Random.nextInt(queue.size)
        player.setShuffle(true)
        val resumeMs = if (_state.value.mode == DedicatedMediaMode.AUDIOBOOK) loadResumeMs(queue[startIndex].id) else 0L
        player.play(queue, startIndex, resumeMs)
        _state.value = _state.value.copy(selectedPlaylist = fresh)
    }

    fun toggleShuffle() {
        player.toggleShuffle()
    }

    fun playPlaylistItem(playlist: Playlist, media: MediaFile) = viewModelScope.launch {
        val queue = playlist.items.map { it.mediaFile }.filter { it.isPlayable }
        val index = queue.indexOfFirst { it.id == media.id }
        if (index < 0) return@launch
        val resumeMs = if (_state.value.mode == DedicatedMediaMode.AUDIOBOOK) loadResumeMs(media.id) else 0L
        player.play(queue, index, resumeMs)
    }

    fun visibleLibrary(): List<MediaFile> {
        val q = _state.value.query.trim()
        val base = if (_state.value.playlistMode) {
            _state.value.selectedPlaylist?.items.orEmpty().map { it.mediaFile }.filter { it.isPlayable }
        } else {
            _state.value.library
        }
        return base.filter {
            val visibilityMatches = _state.value.playlistMode || when (_state.value.libraryVisibility) {
                LibraryVisibility.ALL -> true
                LibraryVisibility.PERSONAL -> !it.isPublic
                LibraryVisibility.PUBLIC -> it.isPublic
            }
            visibilityMatches && (q.isBlank() || it.displayTitle.contains(q, true) || it.description.contains(q, true))
        }
    }

    fun visiblePlaylists(): List<Playlist> {
        val q = _state.value.playlistQuery.trim()
        return _state.value.playlists.filter {
            q.isBlank() || it.name.contains(q, true) || it.description.contains(q, true)
        }
    }

    private suspend fun loadResumeMs(mediaFileId: Int): Long {
        return when (val result = safeApiCall { playbackApi.position(tokenStore.userId, mediaFileId) }) {
            is ApiResult.Success -> result.data.takeIf { it.found && !it.completed && it.position > 0.0 }?.position?.times(1_000)?.toLong() ?: 0L
            else -> 0L
        }
    }

    val bearerToken: String get() = tokenStore.token.orEmpty()
}

@Composable
fun DedicatedMediaPlayerScreen(
    mode: DedicatedMediaMode,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
    initialMediaId: Int? = null,
    initialPlaylistId: Int? = null,
    viewModel: DedicatedMediaPlayerViewModel = hiltViewModel(key = "player-${mode.name}"),
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val playerState by viewModel.player.state.collectAsState()
    // Every input visibleLibrary() reads must be a key. Missing playlistMode/selectedPlaylist
    // here meant picking a playlist swapped the source list but never recomputed the rendered
    // one, so the playlist's books were invisible and only the auto-played first track
    // appeared to work. libraryVisibility had the same defect for the All/Personal/Public pills.
    val visibleLibrary = remember(
        state.library,
        state.query,
        state.playlistMode,
        state.selectedPlaylist,
        state.libraryVisibility,
    ) { viewModel.visibleLibrary() }
    val visiblePlaylists = remember(state.playlists, state.playlistQuery) { viewModel.visiblePlaylists() }
    var editing by remember { mutableStateOf<MediaFile?>(null) }

    editing?.let { media ->
        EditMediaDialog(
            media = media,
            onDismiss = { editing = null },
            onSave = { t, d, p -> viewModel.updateMedia(media.id, t, d, p); editing = null },
        )
    }

    LaunchedEffect(mode, initialMediaId, initialPlaylistId) {
        viewModel.initialize(mode, initialMediaId, initialPlaylistId)
    }

    ScreenScaffold(title = mode.title, onBack = onBack, gutter = Spacing.gutter) { padding ->
        if (state.loading) {
            LoadingBox(padding)
            return@ScreenScaffold
        }

        // The `.background(Brush.verticalGradient(MediaBg, MaterialTheme.colorScheme.surface))` that used to be here
        // loaded the shared chrome and then painted over it, which is how this screen came to be
        // the one blue-purple page in a gold-and-parchment app — and now the one page that would
        // not have matched the new palette either. The scaffold's ground shows through.
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = Spacing.xl),
        ) {
            // A card here repeated `mode.title` — the exact string the app bar directly above it
            // was already showing — with "Lexicon media player" under it. Both are gone.

            item {
                when (mode) {
                    DedicatedMediaMode.VIDEO -> VideoNowPlayingSection(
                        player = viewModel.player,
                        playerState = playerState,
                        onPrevious = viewModel.player::previous,
                        onPlayPause = viewModel.player::togglePlayPause,
                        onNext = viewModel.player::next,
                        onSeek = viewModel.player::seekToFraction,
                        onToggleAutoAdvance = { viewModel.player.setAutoAdvance(!playerState.autoAdvance) },
                        onToggleShuffle = viewModel::toggleShuffle,
                    )
                    DedicatedMediaMode.AUDIOBOOK -> AudiobookNowPlayingSection(
                        playerState = playerState,
                        inPlaylist = state.playlistMode,
                        playlistName = state.selectedPlaylist?.name.orEmpty(),
                        onSeek = viewModel.player::seekToFraction,
                        onBack30 = { viewModel.player.seekBy(-30_000L) },
                        onPlayPause = viewModel.player::togglePlayPause,
                        onForward30 = { viewModel.player.seekBy(30_000L) },
                        onVolume = viewModel.player::setVolume,
                        onSpeed = { viewModel.player.setSpeed(it) },
                    )
                    DedicatedMediaMode.AUDIO -> NowPlayingSection(
                        playerState = playerState,
                        inPlaylist = state.selectedPlaylist != null,
                        playlistName = state.selectedPlaylist?.name.orEmpty(),
                        onSeek = viewModel.player::seekToFraction,
                        onPrevious = viewModel.player::previous,
                        onPlayPause = viewModel.player::togglePlayPause,
                        onNext = viewModel.player::next,
                        onVolume = viewModel.player::setVolume,
                        onToggleAutoAdvance = { viewModel.player.setAutoAdvance(!playerState.autoAdvance) },
                        onToggleShuffle = viewModel::toggleShuffle,
                    )
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (state.playlistMode) {
                            state.selectedPlaylist?.name ?: "Playlist"
                        } else {
                            mode.browserTitle
                        },
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (state.playlistMode) {
                        // Was a clickable `Surface` painted in a hardcoded red with white text —
                        // no ripple, no disabled state, and below the touch minimum at 6dp of
                        // vertical padding.
                        LexButton(
                            text = "Exit playlist",
                            onClick = { viewModel.exitPlaylistMode() },
                            tone = LexTone.Destructive,
                        )
                    }
                }
            }

            item {
                Row(Modifier.fillMaxWidth()) {
                    UnderlineTab("Library", state.tab == PlayerPageTab.LIBRARY, Modifier.weight(1f)) { viewModel.setTab(0) }
                    UnderlineTab("Playlists", state.tab == PlayerPageTab.PLAYLISTS, Modifier.weight(1f)) { viewModel.setTab(1) }
                }
            }

            item {
                OutlinedTextField(
                    value = if (state.tab == PlayerPageTab.LIBRARY) state.query else state.playlistQuery,
                    onValueChange = { if (state.tab == PlayerPageTab.LIBRARY) viewModel.setQuery(it) else viewModel.setPlaylistQuery(it) },
                    placeholder = {
                        Text(
                            if (state.tab == PlayerPageTab.LIBRARY) "Search ${mode.searchNoun}..." else "Search playlists...",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        cursorColor = MaterialTheme.colorScheme.primary,
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
                )
            }

            if (state.tab == PlayerPageTab.LIBRARY && !state.playlistMode) {
                item {
                    // Three hand-rolled `Surface` pills, which had neither ripple nor a selected
                    // state Material would recognise, in a plain Row.
                    ActionRow(Modifier.padding(vertical = Spacing.xxs)) {
                        LibraryVisibility.entries.forEach { option ->
                            LexChip(
                                label = option.name.lowercase().replaceFirstChar { it.uppercase() },
                                selected = state.libraryVisibility == option,
                                onClick = { viewModel.setLibraryVisibility(option) },
                            )
                        }
                    }
                }
            }

            state.error?.let { err ->
                item {
                    Text(err, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = Spacing.sm))
                }
            }

            if (state.tab == PlayerPageTab.LIBRARY) {
                if (visibleLibrary.isEmpty()) {
                    item { EmptyBox(PaddingValues(24.dp), "No ${mode.searchNoun} files found") }
                } else {
                    items(visibleLibrary, key = { it.id }) { media ->
                        Box(Modifier.padding(vertical = Spacing.xs)) {
                            AudioItemRow(
                                media = media,
                                active = playerState.mediaId == media.id.toString(),
                                isPlaying = playerState.isPlaying,
                                onPlay = {
                                    viewModel.playMedia(media)
                                    if (mode == DedicatedMediaMode.VIDEO) onOpenPlayer()
                                },
                                onEdit = { editing = media },
                                onDownload = {
                                    val url = "${ApiUrls.LEXICON}api/media/${media.id}/download"
                                    val fileName = media.originalFilename.ifBlank { media.filename.ifBlank { "media_${media.id}" } }
                                    val request = DownloadManager.Request(Uri.parse(url))
                                        .addRequestHeader("Authorization", "Bearer ${viewModel.bearerToken}")
                                        .setTitle(media.displayTitle)
                                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                                    (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                                    Toast.makeText(context, "Downloading ${media.displayTitle}", Toast.LENGTH_SHORT).show()
                                },
                            )
                        }
                    }
                }
            } else {
                if (visiblePlaylists.isEmpty()) {
                    item { EmptyBox(PaddingValues(24.dp), "No playlists found") }
                } else {
                    items(visiblePlaylists, key = { it.id }) { playlist ->
                        Box(Modifier.padding(vertical = Spacing.xs)) {
                            PlaylistMiniCard(playlist) {
                                viewModel.loadPlaylist(playlist.id)
                                if (mode == DedicatedMediaMode.VIDEO) onOpenPlayer()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AudiobookNowPlayingSection(
    playerState: com.alexdyakin.lexicon.media.PlayerState,
    inPlaylist: Boolean,
    playlistName: String,
    onSeek: (Float) -> Unit,
    onBack30: () -> Unit,
    onPlayPause: () -> Unit,
    onForward30: () -> Unit,
    onVolume: (Float) -> Unit,
    onSpeed: (Float) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
    ) {
        if (playerState.mediaId == null) {
            Text(
                "Select an audiobook to start listening",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 16.dp),
            )
            return@Card
        }
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("NOW PLAYING", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, letterSpacing = 1.5.sp)
                Text(playerState.title.ifBlank { "Untitled" }, color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (playerState.subtitle.isNotBlank()) {
                    Text(playerState.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (inPlaylist && playlistName.isNotBlank()) {
                    Text(playlistName, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(formatMs(playerState.positionMs), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = playerState.progress,
                    onValueChange = onSeek,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary, inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant),
                )
                Text(formatMs(playerState.durationMs), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            }

            // The "↺ 30s" / "30s ↻" labels were typographic arrows, which do not tint, do not
            // scale with the icon size and carry no content description. Real icons now.
            TransportBar(
                center = {
                    PlayPauseButton(
                        playing = playerState.isPlaying,
                        onClick = onPlayPause,
                        size = 60.dp,
                    )
                },
                leading = {
                    TransportButton(
                        icon = Icons.Filled.Replay30,
                        contentDescription = "Back 30 seconds",
                        onClick = onBack30,
                    )
                },
                trailing = {
                    TransportButton(
                        icon = Icons.Filled.Forward30,
                        contentDescription = "Forward 30 seconds",
                        onClick = onForward30,
                    )
                },
            )

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    Icons.Filled.VolumeUp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(Sizes.icon),
                )
                Slider(
                    value = playerState.volume,
                    onValueChange = onVolume,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary, inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant),
                )
                Text("${(playerState.volume * 100).toInt()}%", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Speed", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge)
                // Six fixed chips needed ~318dp in a 256dp card, so the last of them were
                // clipped. ActionRow wraps to a second line instead.
                ActionRow {
                    listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { sp ->
                        val active = kotlin.math.abs(playerState.speed - sp) < 0.01f
                        val label = if (sp % 1f == 0f) sp.toInt().toString() else sp.toString()
                        LexChip(label = "${label}x", selected = active, onClick = { onSpeed(sp) })
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoNowPlayingSection(
    player: PlayerConnection,
    playerState: com.alexdyakin.lexicon.media.PlayerState,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    onToggleAutoAdvance: () -> Unit,
    onToggleShuffle: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                if (playerState.mediaId != null && playerState.isVideo) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = true
                                controllerShowTimeoutMs = 3_500
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                                this.player = player.playerOrNull()
                            }
                        },
                        update = { it.player = player.playerOrNull() },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text("Select a video to start watching", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            }

            Column(Modifier.fillMaxWidth().padding(bottom = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(
                    if (playerState.mediaId != null) playerState.title.ifBlank { "Untitled video" } else "Nothing playing",
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (playerState.mediaId != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(formatMs(playerState.positionMs), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                        Slider(
                            value = playerState.progress,
                            onValueChange = onSeek,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary, inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant),
                        )
                        Text(formatMs(playerState.durationMs), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    }
                    // Symmetric before, but only because both pills were weighted — which meant
                    // each got 102dp for content needing 116dp, so both labels permanently
                    // ellipsised to "Previ…". Fixed slots fit without truncating anything.
                    TransportBar(
                        center = {
                            PlayPauseButton(
                                playing = playerState.isPlaying,
                                onClick = onPlayPause,
                                size = 60.dp,
                            )
                        },
                        leading = {
                            TransportButton(
                                icon = Icons.Filled.SkipPrevious,
                                contentDescription = "Previous",
                                onClick = onPrevious,
                                enabled = playerState.hasPrevious,
                            )
                        },
                        trailing = {
                            TransportButton(
                                icon = Icons.Filled.SkipNext,
                                contentDescription = "Next",
                                onClick = onNext,
                                enabled = playerState.hasNext,
                            )
                        },
                    )
                    ActionRow {
                        LabeledSwitch("Auto-play", playerState.autoAdvance, onCheckedChange = { onToggleAutoAdvance() })
                        LabeledSwitch("Shuffle", playerState.shuffle, onCheckedChange = { onToggleShuffle() })
                    }
                }
            }
        }
    }
}

@Composable
private fun UnderlineTab(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // The whole tab is the tap target, so it has to clear the touch minimum; 10dp of
            // vertical padding around a single line left it around 30dp tall.
            modifier = Modifier
                .heightIn(min = Sizes.touch)
                .wrapContentHeight(Alignment.CenterVertically)
                .padding(horizontal = Spacing.sm),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
        )
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "N/A"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) "%.2f GB".format(mb / 1024) else "%.2f MB".format(mb)
}

@Composable
private fun AudioItemRow(
    media: MediaFile,
    active: Boolean,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onEdit: () -> Unit,
    onDownload: () -> Unit,
) {
    Surface(
        color = if (active) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onPlay),
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(media.displayTitle, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (media.description.isNotBlank()) {
                    Text(media.description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (media.isPublic) "Public" else "Personal", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    Text(formatSize(media.fileSize), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                }
            }
            // Three trailing elements, none of them a real control: a `♫` glyph, and two
            // clickable `Surface`es containing `⬇` and `✏️`. The emoji took no tint, carried no
            // content description, and at 10×6dp of padding both buttons were well under the
            // touch minimum. These are the same two icon buttons the rest of the app uses.
            if (active && isPlaying) {
                Icon(
                    Icons.Filled.GraphicEq,
                    contentDescription = "Playing",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(Sizes.iconSm),
                )
            }
            LexIconButton(
                icon = Icons.Filled.Download,
                contentDescription = "Download",
                onClick = onDownload,
            )
            LexIconButton(
                icon = Icons.Filled.Edit,
                contentDescription = "Edit",
                onClick = onEdit,
            )
        }
    }
}

@Composable
private fun PlaylistMiniCard(playlist: Playlist, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.QueueMusic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(Sizes.iconLg),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(playlist.name, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (playlist.description.isNotBlank()) {
                    Text(playlist.description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(10.dp)) {
                    Text(
                        playlist.mediaType,
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun EditMediaDialog(
    media: MediaFile,
    onDismiss: () -> Unit,
    onSave: (String, String, Boolean) -> Unit,
) {
    var title by remember(media.id) { mutableStateOf(media.title.ifBlank { media.displayTitle }) }
    var description by remember(media.id) { mutableStateOf(media.description) }
    var isPublic by remember(media.id) { mutableStateOf(media.isPublic) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Edit media", color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true)
                OutlinedTextField(description, { description = it }, label = { Text("Description") })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(
                        checked = isPublic,
                        onCheckedChange = { isPublic = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary),
                    )
                    Text(if (isPublic) "Public" else "Personal", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(title, description, isPublic) }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) } },
    )
}

private fun formatMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

@Composable
private fun NowPlayingSection(
    playerState: com.alexdyakin.lexicon.media.PlayerState,
    inPlaylist: Boolean,
    playlistName: String,
    onSeek: (Float) -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onVolume: (Float) -> Unit,
    onToggleAutoAdvance: () -> Unit,
    onToggleShuffle: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
    ) {
        if (playerState.mediaId == null) {
            Text(
                "Select a track from the library to start listening",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 16.dp),
            )
            return@Card
        }

        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("NOW PLAYING", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, letterSpacing = 1.5.sp)
                Text(playerState.title.ifBlank { "Untitled" }, color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (playerState.subtitle.isNotBlank()) {
                    Text(playerState.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (inPlaylist && playlistName.isNotBlank()) {
                    Text(playlistName, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(formatMs(playerState.positionMs), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = playerState.progress,
                    onValueChange = onSeek,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary, inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant),
                )
                Text(formatMs(playerState.durationMs), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            }

            // This is the row that prompted the redesign. Both pills were unweighted and
            // content-sized, so Compose measured "Previous" first at the ~128dp it wanted and
            // handed "Next" — measured last — the ~76dp left over when it needed ~99dp. Next came
            // out narrower, its label clipped, and the play button sat ~14dp right of centre.
            TransportBar(
                center = {
                    PlayPauseButton(
                        playing = playerState.isPlaying,
                        onClick = onPlayPause,
                        size = 60.dp,
                    )
                },
                leading = {
                    TransportButton(
                        icon = Icons.Filled.SkipPrevious,
                        contentDescription = "Previous",
                        onClick = onPrevious,
                        enabled = playerState.hasPrevious,
                    )
                },
                trailing = {
                    TransportButton(
                        icon = Icons.Filled.SkipNext,
                        contentDescription = "Next",
                        onClick = onNext,
                        enabled = playerState.hasNext,
                    )
                },
            )

            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(
                        Icons.Filled.VolumeUp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(Sizes.icon),
                    )
                    Slider(
                        value = playerState.volume,
                        onValueChange = onVolume,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary, inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant),
                    )
                    Text("${(playerState.volume * 100).toInt()}%", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                }
                ActionRow {
                    LabeledSwitch("Auto-play", playerState.autoAdvance, onCheckedChange = { onToggleAutoAdvance() })
                    LabeledSwitch("Shuffle", playerState.shuffle, onCheckedChange = { onToggleShuffle() })
                }
            }
        }
    }
}

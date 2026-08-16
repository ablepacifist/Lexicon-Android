package com.alexdyakin.lexicon.ui.lexicon

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
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
import com.alexdyakin.lexicon.ui.components.ScreenScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.random.Random

private val MediaBg = Color(0xFF1A1A2E)
private val MediaPanel = Color(0xFF16213E)
private val MediaButton = Color(0xFF0F3460)
private val MediaButtonAlt = Color(0xFF2A2A4A)
private val MediaText = Color(0xFFE0E0E0)
private val MediaMuted = Color(0xFFAAAAAA)
private val MediaAccent = Color(0xFF667EEA)

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

    ScreenScaffold(title = mode.title, onBack = onBack, background = R.drawable.bg_dashboard) { padding ->
        if (state.loading) {
            LoadingBox(padding)
            return@ScreenScaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(
                    Brush.verticalGradient(listOf(MediaBg, MediaPanel))
                ),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                Surface(
                    color = MediaPanel,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(mode.title, color = MediaText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text("Lexicon media player", color = MediaMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

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
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (state.playlistMode) "📋 ${state.selectedPlaylist?.name ?: "Playlist"}" else mode.browserTitle,
                        color = MediaText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (state.playlistMode) {
                        Surface(
                            color = Color(0xFFE74C3C),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.clickable { viewModel.exitPlaylistMode() },
                        ) {
                            Text(
                                "Exit Playlist Mode",
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }

            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    UnderlineTab("📚 Library", state.tab == PlayerPageTab.LIBRARY, Modifier.weight(1f)) { viewModel.setTab(0) }
                    UnderlineTab("📋 Playlists", state.tab == PlayerPageTab.PLAYLISTS, Modifier.weight(1f)) { viewModel.setTab(1) }
                }
            }

            item {
                OutlinedTextField(
                    value = if (state.tab == PlayerPageTab.LIBRARY) state.query else state.playlistQuery,
                    onValueChange = { if (state.tab == PlayerPageTab.LIBRARY) viewModel.setQuery(it) else viewModel.setPlaylistQuery(it) },
                    placeholder = {
                        Text(
                            if (state.tab == PlayerPageTab.LIBRARY) "Search ${mode.searchNoun}..." else "Search playlists...",
                            color = Color(0xFF666666),
                        )
                    },
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = MediaMuted) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF0E1628),
                        unfocusedContainerColor = Color(0xFF0E1628),
                        focusedBorderColor = MediaAccent,
                        unfocusedBorderColor = MediaButtonAlt,
                        focusedTextColor = MediaText,
                        unfocusedTextColor = MediaText,
                        cursorColor = MediaAccent,
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }

            if (state.tab == PlayerPageTab.LIBRARY && !state.playlistMode) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterPill("All", state.libraryVisibility == LibraryVisibility.ALL) { viewModel.setLibraryVisibility(LibraryVisibility.ALL) }
                        FilterPill("Personal", state.libraryVisibility == LibraryVisibility.PERSONAL) { viewModel.setLibraryVisibility(LibraryVisibility.PERSONAL) }
                        FilterPill("Public", state.libraryVisibility == LibraryVisibility.PUBLIC) { viewModel.setLibraryVisibility(LibraryVisibility.PUBLIC) }
                    }
                }
            }

            state.error?.let { err ->
                item {
                    Text(err, color = Color(0xFFE74C3C), modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                }
            }

            if (state.tab == PlayerPageTab.LIBRARY) {
                if (visibleLibrary.isEmpty()) {
                    item { EmptyBox(PaddingValues(24.dp), "No ${mode.searchNoun} files found") }
                } else {
                    items(visibleLibrary, key = { it.id }) { media ->
                        Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
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
                        Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
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
        colors = CardDefaults.cardColors(containerColor = MediaPanel),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        if (playerState.mediaId == null) {
            Text(
                "📚 Select an audiobook to start listening",
                color = MediaMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 16.dp),
            )
            return@Card
        }
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("NOW PLAYING", color = Color(0xFF888888), style = MaterialTheme.typography.labelMedium, letterSpacing = 1.5.sp)
                Text(playerState.title.ifBlank { "Untitled" }, color = Color.White, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (playerState.subtitle.isNotBlank()) {
                    Text(playerState.subtitle, color = MediaMuted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (inPlaylist && playlistName.isNotBlank()) {
                    Text("📚 $playlistName", color = MediaAccent, style = MaterialTheme.typography.labelMedium)
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(formatMs(playerState.positionMs), color = MediaMuted, style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = playerState.progress,
                    onValueChange = onSeek,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = MediaAccent, activeTrackColor = MediaAccent, inactiveTrackColor = MediaButtonAlt),
                )
                Text(formatMs(playerState.durationMs), color = MediaMuted, style = MaterialTheme.typography.labelMedium)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(color = MediaButtonAlt, shape = RoundedCornerShape(50.dp), modifier = Modifier.clickable(onClick = onBack30)) {
                    Text("↺ 30s", color = MediaText, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
                }
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .background(Brush.linearGradient(listOf(Color(0xFF667EEA), Color(0xFF764BA2))), CircleShape)
                        .clickable(onClick = onPlayPause),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (playerState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playerState.isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(30.dp),
                    )
                }
                Surface(color = MediaButtonAlt, shape = RoundedCornerShape(50.dp), modifier = Modifier.clickable(onClick = onForward30)) {
                    Text("30s ↻", color = MediaText, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("🔊", style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = playerState.volume,
                    onValueChange = onVolume,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = MediaAccent, activeTrackColor = MediaAccent, inactiveTrackColor = MediaButtonAlt),
                )
                Text("${(playerState.volume * 100).toInt()}%", color = MediaAccent, style = MaterialTheme.typography.labelMedium)
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("⚡ Speed", color = Color(0xFFCCCCCC), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { sp ->
                        val active = kotlin.math.abs(playerState.speed - sp) < 0.01f
                        val label = if (sp % 1f == 0f) sp.toInt().toString() else sp.toString()
                        Surface(
                            color = if (active) MediaAccent else Color.Transparent,
                            contentColor = if (active) Color.White else MediaAccent,
                            shape = RoundedCornerShape(16.dp),
                            border = androidx.compose.foundation.BorderStroke(2.dp, MediaAccent),
                            modifier = Modifier.clickable { onSpeed(sp) },
                        ) {
                            Text("${label}x", modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                        }
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
        colors = CardDefaults.cardColors(containerColor = MediaPanel),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
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
                    Text("Select a video to start watching", color = MediaMuted, textAlign = TextAlign.Center)
                }
            }

            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (playerState.mediaId != null) playerState.title.ifBlank { "Untitled video" } else "Nothing playing",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (playerState.mediaId != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(formatMs(playerState.positionMs), color = MediaMuted, style = MaterialTheme.typography.labelMedium)
                        Slider(
                            value = playerState.progress,
                            onValueChange = onSeek,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(thumbColor = MediaAccent, activeTrackColor = MediaAccent, inactiveTrackColor = MediaButtonAlt),
                        )
                        Text(formatMs(playerState.durationMs), color = MediaMuted, style = MaterialTheme.typography.labelMedium)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(color = MediaButtonAlt, shape = RoundedCornerShape(50.dp), modifier = Modifier.clickable(enabled = playerState.hasPrevious, onClick = onPrevious)) {
                            Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Filled.SkipPrevious, "Previous", tint = if (playerState.hasPrevious) MediaText else MediaMuted)
                                Text("Previous", color = if (playerState.hasPrevious) MediaText else MediaMuted, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .background(Brush.linearGradient(listOf(Color(0xFF667EEA), Color(0xFF764BA2))), CircleShape)
                                .clickable(onClick = onPlayPause),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (playerState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (playerState.isPlaying) "Pause" else "Play",
                                tint = Color.White,
                                modifier = Modifier.size(30.dp),
                            )
                        }
                        Surface(color = MediaButtonAlt, shape = RoundedCornerShape(50.dp), modifier = Modifier.clickable(enabled = playerState.hasNext, onClick = onNext)) {
                            Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Next", color = if (playerState.hasNext) MediaText else MediaMuted, style = MaterialTheme.typography.labelLarge)
                                Icon(Icons.Filled.SkipNext, "Next", tint = if (playerState.hasNext) MediaText else MediaMuted)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Auto-play", color = Color(0xFFCCCCCC), style = MaterialTheme.typography.labelLarge)
                            Switch(checked = playerState.autoAdvance, onCheckedChange = { onToggleAutoAdvance() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = MediaAccent, uncheckedTrackColor = MediaButtonAlt))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("🔀 Shuffle", color = Color(0xFFCCCCCC), style = MaterialTheme.typography.labelLarge)
                            Switch(checked = playerState.shuffle, onCheckedChange = { onToggleShuffle() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = MediaAccent, uncheckedTrackColor = MediaButtonAlt))
                        }
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
            color = if (selected) MediaAccent else Color(0xFF888888),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(vertical = 10.dp),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(if (selected) MediaAccent else Color(0xFF2A2A4A)),
        )
    }
}

@Composable
private fun FilterPill(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) MediaAccent else Color.Transparent,
        contentColor = if (selected) Color.White else MediaMuted,
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(2.dp, if (selected) MediaAccent else MediaButtonAlt),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
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
        color = if (active) Color(0xFF1A2744) else Color(0xFF0E1628),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (active) MediaAccent else Color(0xFF2A2A4A)),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onPlay),
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(media.displayTitle, color = MediaText, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (media.description.isNotBlank()) {
                    Text(media.description, color = Color(0xFF888888), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (media.isPublic) "🌐 Public" else "🔒 Personal", color = Color(0xFF888888), style = MaterialTheme.typography.labelSmall)
                    Text(formatSize(media.fileSize), color = Color(0xFF888888), style = MaterialTheme.typography.labelSmall)
                }
            }
            if (active && isPlaying) {
                Text("♫", color = MediaAccent, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 6.dp))
            }
            Surface(color = MediaButtonAlt, shape = RoundedCornerShape(8.dp), modifier = Modifier.padding(end = 6.dp).clickable(onClick = onDownload)) {
                Text("⬇", color = MediaText, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
            Surface(color = MediaButtonAlt, shape = RoundedCornerShape(8.dp), modifier = Modifier.clickable(onClick = onEdit)) {
                Text("✏️", modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
    }
}

@Composable
private fun PlaylistMiniCard(playlist: Playlist, onClick: () -> Unit) {
    Surface(
        color = Color(0xFF0E1628),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2A2A4A)),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("🎵", style = MaterialTheme.typography.headlineSmall)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(playlist.name, color = MediaText, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (playlist.description.isNotBlank()) {
                    Text(playlist.description, color = Color(0xFF888888), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Surface(color = MediaAccent, shape = RoundedCornerShape(10.dp)) {
                    Text(
                        playlist.mediaType,
                        color = Color.White,
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
        containerColor = MediaPanel,
        title = { Text("✏️ Edit Media", color = MediaText) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true)
                OutlinedTextField(description, { description = it }, label = { Text("Description") })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(
                        checked = isPublic,
                        onCheckedChange = { isPublic = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = MediaAccent),
                    )
                    Text(if (isPublic) "Public" else "Personal", color = MediaText)
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(title, description, isPublic) }, colors = ButtonDefaults.buttonColors(containerColor = MediaAccent)) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = MediaMuted) } },
    )
}

@Composable
private fun PageTabChip(selected: Boolean, text: String, onClick: () -> Unit) {
    Surface(
        color = if (selected) MediaButton else MediaButtonAlt,
        contentColor = MediaText,
        shape = RoundedCornerShape(999.dp),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(text, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), fontWeight = FontWeight.SemiBold)
    }
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
        colors = CardDefaults.cardColors(containerColor = MediaPanel),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        if (playerState.mediaId == null) {
            Text(
                "Select a track from the library to start listening",
                color = MediaMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 16.dp),
            )
            return@Card
        }

        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("NOW PLAYING", color = Color(0xFF888888), style = MaterialTheme.typography.labelMedium, letterSpacing = 1.5.sp)
                Text(playerState.title.ifBlank { "Untitled" }, color = Color.White, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (playerState.subtitle.isNotBlank()) {
                    Text(playerState.subtitle, color = MediaMuted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (inPlaylist && playlistName.isNotBlank()) {
                    Text("\uD83D\uDCCB $playlistName", color = MediaAccent, style = MaterialTheme.typography.labelMedium)
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(formatMs(playerState.positionMs), color = MediaMuted, style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = playerState.progress,
                    onValueChange = onSeek,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = MediaAccent, activeTrackColor = MediaAccent, inactiveTrackColor = MediaButtonAlt),
                )
                Text(formatMs(playerState.durationMs), color = MediaMuted, style = MaterialTheme.typography.labelMedium)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    color = MediaButtonAlt,
                    shape = RoundedCornerShape(50.dp),
                    modifier = Modifier.clickable(enabled = playerState.hasPrevious, onClick = onPrevious),
                ) {
                    Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", tint = if (playerState.hasPrevious) MediaText else MediaMuted)
                        Text("Previous", color = if (playerState.hasPrevious) MediaText else MediaMuted, style = MaterialTheme.typography.labelLarge)
                    }
                }

                Surface(
                    shape = CircleShape,
                    modifier = Modifier.size(60.dp).clickable(onClick = onPlayPause),
                    color = Color.Transparent,
                ) {
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .background(Brush.linearGradient(listOf(Color(0xFF667EEA), Color(0xFF764BA2))), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (playerState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (playerState.isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }

                Surface(
                    color = MediaButtonAlt,
                    shape = RoundedCornerShape(50.dp),
                    modifier = Modifier.clickable(enabled = playerState.hasNext, onClick = onNext),
                ) {
                    Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Next", color = if (playerState.hasNext) MediaText else MediaMuted, style = MaterialTheme.typography.labelLarge)
                        Icon(Icons.Filled.SkipNext, contentDescription = "Next", tint = if (playerState.hasNext) MediaText else MediaMuted)
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("\uD83D\uDD0A", style = MaterialTheme.typography.titleMedium)
                    Slider(
                        value = playerState.volume,
                        onValueChange = onVolume,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(thumbColor = MediaAccent, activeTrackColor = MediaAccent, inactiveTrackColor = MediaButtonAlt),
                    )
                    Text("${(playerState.volume * 100).toInt()}%", color = MediaAccent, style = MaterialTheme.typography.labelMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Auto-play", color = Color(0xFFCCCCCC), style = MaterialTheme.typography.labelLarge)
                        Switch(
                            checked = playerState.autoAdvance,
                            onCheckedChange = { onToggleAutoAdvance() },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = MediaAccent, uncheckedTrackColor = MediaButtonAlt),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("\uD83D\uDD00 Shuffle", color = Color(0xFFCCCCCC), style = MaterialTheme.typography.labelLarge)
                        Switch(
                            checked = playerState.shuffle,
                            onCheckedChange = { onToggleShuffle() },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = MediaAccent, uncheckedTrackColor = MediaButtonAlt),
                        )
                    }
                }
            }
        }
    }
}

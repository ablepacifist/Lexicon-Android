package com.alexdyakin.lexicon.ui.lexicon

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexdyakin.lexicon.R
import com.alexdyakin.lexicon.data.ApiResult
import com.alexdyakin.lexicon.data.ApiUrls
import com.alexdyakin.lexicon.data.MediaFile
import com.alexdyakin.lexicon.data.MediaUpdateRequest
import com.alexdyakin.lexicon.data.TorrentProgress
import com.alexdyakin.lexicon.data.TokenStore
import com.alexdyakin.lexicon.data.api.MediaApi
import com.alexdyakin.lexicon.data.api.PlaylistApi
import com.alexdyakin.lexicon.data.api.TorrentApi
import com.alexdyakin.lexicon.data.safeApiCall
import com.alexdyakin.lexicon.ui.components.ActionRow
import com.alexdyakin.lexicon.ui.components.LaunchedWhileResumed
import com.alexdyakin.lexicon.ui.components.LexButton
import com.alexdyakin.lexicon.ui.components.LexChip
import com.alexdyakin.lexicon.ui.components.LexIconButton
import com.alexdyakin.lexicon.ui.components.LexTone
import com.alexdyakin.lexicon.ui.components.ScreenScaffold
import com.alexdyakin.lexicon.ui.components.SplitActions
import com.alexdyakin.lexicon.ui.components.SwitchRow
import com.alexdyakin.lexicon.ui.theme.Elevation
import com.alexdyakin.lexicon.ui.theme.Radii
import com.alexdyakin.lexicon.ui.theme.Spacing
import com.alexdyakin.lexicon.ui.theme.Status
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject

enum class BrowseSource { NONE, MY, PUBLIC, SEARCH }

/** One line describing where a torrent has got to, mirroring the website's wording. */
private fun torrentStatusText(torrent: TorrentProgress): String = when {
    torrent.status == "FAILED" -> "Failed"
    torrent.status == "COMPLETED" -> "Added to your library"
    torrent.status == "IMPORTING" -> "Adding to Lexicon..."
    torrent.status == "PREPARING" -> "Looking up the torrent..."
    torrent.stalled -> "Stalled - check that the VPN is still connected"
    torrent.downloadSpeed > 0 ->
        "${torrent.percent}% - %.1f MB/s".format(torrent.downloadSpeed / (1024.0 * 1024.0))
    else -> "${torrent.percent}% - no peers yet"
}

data class MediaUploadUiState(
    val saving: Boolean = false,
    val loading: Boolean = false,
    val message: String? = null,
    val mediaFiles: List<MediaFile> = emptyList(),
    val browseSource: BrowseSource = BrowseSource.NONE,
    /** Null until the server has been asked whether torrent downloads are possible. */
    val torrentReady: Boolean? = null,
    val torrentBlockedReason: String = "",
    val torrents: List<TorrentProgress> = emptyList(),
)

@HiltViewModel
class MediaUploadViewModel @Inject constructor(
    private val api: MediaApi,
    private val playlistApi: PlaylistApi,
    private val torrentApi: TorrentApi,
    private val tokenStore: TokenStore,
) : ViewModel() {

    private val _state = MutableStateFlow(MediaUploadUiState())
    val state = _state.asStateFlow()

    val authToken: String get() = tokenStore.token.orEmpty()

    fun file(bytes: ByteArray, filename: String, contentType: String?, title: String, description: String, isPublic: Boolean, type: String) = send {
        val part = MultipartBody.Part.createFormData("file", filename, bytes.toRequestBody(contentType?.toMediaTypeOrNull()))
        api.upload(part, tokenStore.userId.toString().toRequestBody(), title.toRequestBody(), description.toRequestBody(), isPublic.toString().toRequestBody(), type.toRequestBody())
    }

    fun link(url: String, title: String, description: String, isPublic: Boolean, type: String, downloadType: String) = send { api.uploadFromUrl(url, tokenStore.userId, title, description, isPublic, type, downloadType) }

    fun importPlaylist(url: String, playlistName: String, playlistPublic: Boolean, mediaPublic: Boolean, mediaType: String, downloadType: String) = viewModelScope.launch {
        _state.value = _state.value.copy(saving = true, message = null)
        when (
            val result = safeApiCall {
                playlistApi.importYoutube(url, tokenStore.userId, playlistName, playlistPublic, mediaPublic, mediaType, downloadType)
            }
        ) {
            is ApiResult.Success -> {
                val importId = result.data.importId.takeIf { it.isNotBlank() } ?: "n/a"
                _state.value = _state.value.copy(saving = false, message = "Playlist import started (id: $importId). It may take several minutes.")
            }

            is ApiResult.Failure -> _state.value = _state.value.copy(saving = false, message = result.message)
            ApiResult.Unauthorized -> _state.value = _state.value.copy(saving = false, message = "Sign in again to import playlists.")
        }
    }

    fun loadMyMedia() = loadBrowse(BrowseSource.MY) { api.byUser(tokenStore.userId) }

    fun loadPublicMedia() = loadBrowse(BrowseSource.PUBLIC) { api.public() }

    fun searchMedia(query: String) = loadBrowse(BrowseSource.SEARCH) { api.search(query) }

    fun updateMedia(mediaId: Int, title: String, description: String, isPublic: Boolean) = viewModelScope.launch {
        _state.value = _state.value.copy(saving = true, message = null)
        when (val result = safeApiCall { api.update(mediaId, tokenStore.userId, MediaUpdateRequest(title = title, description = description, isPublic = isPublic)) }) {
            is ApiResult.Success -> {
                _state.value = _state.value.copy(saving = false, message = "Media updated.")
                refreshBrowse()
            }

            is ApiResult.Failure -> _state.value = _state.value.copy(saving = false, message = result.message)
            ApiResult.Unauthorized -> _state.value = _state.value.copy(saving = false, message = "Only the uploader can edit this media.")
        }
    }

    fun deleteMedia(mediaId: Int) = viewModelScope.launch {
        _state.value = _state.value.copy(saving = true, message = null)
        when (val result = safeApiCall { api.delete(mediaId, tokenStore.userId) }) {
            is ApiResult.Success -> {
                _state.value = _state.value.copy(saving = false, message = "Media deleted.")
                refreshBrowse()
            }

            is ApiResult.Failure -> _state.value = _state.value.copy(saving = false, message = result.message)
            ApiResult.Unauthorized -> _state.value = _state.value.copy(saving = false, message = "Only the uploader can delete this media.")
        }
    }

    // ── Torrents ─────────────────────────────────────────────────────────────────
    // The Lexicon machine downloads these through its VPN and keeps only .mp3 and .mp4
    // files. It will not start qBittorrent by itself, so the screen asks first whether a
    // download is possible and says why when it is not.

    private var torrentPoll: Job? = null

    /** Polls while the torrent section is on screen; stopped by [stopWatchingTorrents]. */
    fun watchTorrents() {
        if (torrentPoll?.isActive == true) return
        torrentPoll = viewModelScope.launch {
            checkTorrentReadiness()
            while (isActive) {
                when (val result = safeApiCall { torrentApi.active(tokenStore.userId) }) {
                    is ApiResult.Success -> _state.value = _state.value.copy(torrents = result.data.torrents)
                    // A failed poll is normal when the server restarts; the next one recovers.
                    else -> Unit
                }
                delay(TORRENT_POLL_MS)
            }
        }
    }

    fun stopWatchingTorrents() {
        torrentPoll?.cancel()
        torrentPoll = null
    }

    private suspend fun checkTorrentReadiness() {
        when (val result = safeApiCall { torrentApi.status() }) {
            is ApiResult.Success -> _state.value = _state.value.copy(
                torrentReady = result.data.ready,
                torrentBlockedReason = if (result.data.ready) "" else result.data.message,
            )
            is ApiResult.Failure -> _state.value = _state.value.copy(
                torrentReady = false,
                torrentBlockedReason = result.message,
            )
            ApiResult.Unauthorized -> _state.value = _state.value.copy(
                torrentReady = false,
                torrentBlockedReason = "Sign in again to download torrents.",
            )
        }
    }

    fun submitTorrentFile(bytes: ByteArray, filename: String, title: String, isPublic: Boolean) =
        submitTorrent {
            val part = MultipartBody.Part.createFormData(
                "file", filename, bytes.toRequestBody("application/x-bittorrent".toMediaTypeOrNull()))
            torrentApi.submitFile(
                part,
                tokenStore.userId.toString().toRequestBody(),
                title.toRequestBody(),
                isPublic.toString().toRequestBody())
        }

    fun submitTorrentUrl(url: String, title: String, isPublic: Boolean) =
        submitTorrent { torrentApi.submitUrl(url, tokenStore.userId, title, isPublic) }

    private fun submitTorrent(call: suspend () -> com.alexdyakin.lexicon.data.TorrentSubmitResponse) =
        viewModelScope.launch {
            _state.value = _state.value.copy(saving = true, message = null)
            when (val result = safeApiCall { call() }) {
                is ApiResult.Success -> _state.value = _state.value.copy(
                    saving = false,
                    message = "Downloading \"${result.data.name}\". Only .mp3 and .mp4 files will be added.",
                )
                // The server explains the fixable cases here: no VPN, no space, no media
                // in the torrent, too many at once.
                is ApiResult.Failure -> _state.value = _state.value.copy(saving = false, message = result.message)
                ApiResult.Unauthorized -> _state.value =
                    _state.value.copy(saving = false, message = "Sign in again to download torrents.")
            }
            refreshTorrents()
        }

    fun removeTorrent(infoHash: String) = viewModelScope.launch {
        _state.value = _state.value.copy(saving = true, message = null)
        when (val result = safeApiCall { torrentApi.remove(infoHash, tokenStore.userId) }) {
            is ApiResult.Success -> _state.value =
                _state.value.copy(saving = false, message = "Torrent removed.")
            is ApiResult.Failure -> _state.value = _state.value.copy(saving = false, message = result.message)
            ApiResult.Unauthorized -> _state.value =
                _state.value.copy(saving = false, message = "That torrent is not yours.")
        }
        refreshTorrents()
    }

    private suspend fun refreshTorrents() {
        safeApiCall { torrentApi.active(tokenStore.userId) }.successOrNull?.let { list ->
            _state.value = _state.value.copy(torrents = list.torrents)
        }
    }

    override fun onCleared() {
        stopWatchingTorrents()
        super.onCleared()
    }

    /** For problems the screen itself finds, such as a file it cannot read. */
    fun messageFromScreen(message: String) {
        _state.value = _state.value.copy(message = message)
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    private fun send(call: suspend () -> com.alexdyakin.lexicon.data.MediaUploadResponse) = viewModelScope.launch {
        _state.value = _state.value.copy(saving = true, message = null)
        when (val result = safeApiCall { call() }) {
            is ApiResult.Success -> _state.value = _state.value.copy(saving = false, message = result.data.message.ifBlank { "Media added to the library." })
            is ApiResult.Failure -> _state.value = _state.value.copy(saving = false, message = result.message)
            ApiResult.Unauthorized -> _state.value = _state.value.copy(saving = false, message = "Sign in again to upload media.")
        }
    }

    private fun refreshBrowse() {
        when (_state.value.browseSource) {
            BrowseSource.MY -> loadMyMedia()
            BrowseSource.PUBLIC -> loadPublicMedia()
            BrowseSource.SEARCH -> Unit
            BrowseSource.NONE -> Unit
        }
    }

    private companion object {
        const val TORRENT_POLL_MS = 3000L
    }

    private fun loadBrowse(source: BrowseSource, call: suspend () -> List<MediaFile>) = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, message = null, browseSource = source)
        when (val result = safeApiCall { call() }) {
            is ApiResult.Success -> _state.value = _state.value.copy(loading = false, mediaFiles = result.data)
            is ApiResult.Failure -> _state.value = _state.value.copy(loading = false, message = result.message)
            ApiResult.Unauthorized -> _state.value = _state.value.copy(loading = false, message = "Sign in again to browse media.")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaUploadScreen(onBack: () -> Unit, viewModel: MediaUploadViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    var mode by remember { mutableStateOf("FILE") }
    var uri by remember { mutableStateOf<Uri?>(null) }
    var url by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var mediaType by remember { mutableStateOf("MUSIC") }
    var public by remember { mutableStateOf(false) }
    var downloadType by remember { mutableStateOf("AUDIO_ONLY") }

    var playlistUrl by remember { mutableStateOf("") }
    var playlistName by remember { mutableStateOf("") }
    var playlistPublic by remember { mutableStateOf(true) }
    var mediaPublic by remember { mutableStateOf(false) }
    var playlistMediaType by remember { mutableStateOf("MUSIC") }
    var playlistDownloadType by remember { mutableStateOf("AUDIO_ONLY") }

    var searchQuery by remember { mutableStateOf("") }
    var filterType by remember { mutableStateOf("ALL") }
    var editTarget by remember { mutableStateOf<MediaFile?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { selected -> uri = selected }

    var torrentUri by remember { mutableStateOf<Uri?>(null) }
    var torrentUrl by remember { mutableStateOf("") }
    var torrentTitle by remember { mutableStateOf("") }
    var torrentPublic by remember { mutableStateOf(false) }
    // Separate launcher from the media one: a .torrent is neither audio nor video, and many
    // file providers report it as application/octet-stream regardless of its extension.
    val torrentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { selected ->
        torrentUri = selected
        if (selected != null) {
            torrentUrl = ""
            if (torrentTitle.isBlank()) {
                torrentTitle = (selected.lastPathSegment ?: "")
                    .substringAfterLast('/')
                    .removeSuffix(".torrent")
            }
        }
    }

    // Poll only while this section is open **and** the app is actually in front of the user.
    // The comment here used to claim the former, but DisposableEffect's onDispose fires on
    // leaving composition, not on backgrounding — so with the Torrent tab open and the phone
    // in a pocket, this kept making a network call every three seconds indefinitely.
    LaunchedWhileResumed(mode) {
        if (mode == "TORRENT") {
            try {
                viewModel.watchTorrents()
                awaitCancellation()
            } finally {
                viewModel.stopWatchingTorrents()
            }
        }
    }

    val filtered = remember(state.mediaFiles, filterType) {
        if (filterType == "ALL") state.mediaFiles
        else state.mediaFiles.filter { (it.mediaType.ifBlank { "OTHER" }).equals(filterType, ignoreCase = true) }
    }

    editTarget?.let { target ->
        EditMediaDialog(
            media = target,
            saving = state.saving,
            onDismiss = { editTarget = null },
            onSave = { newTitle, newDescription, newPublic ->
                viewModel.updateMedia(target.id, newTitle.trim(), newDescription.trim(), newPublic)
                editTarget = null
            },
        )
    }

    ScreenScaffold("Add media", onBack, gutter = Spacing.gutter) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(vertical = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item {
                FormCard {
                    Text(
                        "Upload & download",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    // Was "Native Android media management. Text contrast has been tuned for
                    // readability." — a note to the developer, shown to the user.
                    Text(
                        "Add a file from this phone, pull one from a link or a torrent, or " +
                            "import a whole playlist.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                ChoiceChips(
                    options = listOf("FILE", "LINK", "TORRENT", "PLAYLIST", "BROWSE"),
                    selected = mode,
                    onSelect = {
                        mode = it
                        viewModel.clearMessage()
                    },
                )
            }

            if (mode == "FILE" || mode == "LINK") {
                item {
                    FormCard {
                        if (mode == "FILE") {
                            LexButton(
                                text = if (uri == null) {
                                    "Choose audio or video"
                                } else {
                                    "Selected: ${uri?.lastPathSegment ?: "file"}"
                                },
                                onClick = { picker.launch(arrayOf("audio/*", "video/*")) },
                                tone = LexTone.Primary,
                                icon = Icons.Filled.AttachFile,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            UploadField(url, { url = it }, "YouTube or media URL")
                        }

                        UploadField(title, { title = it }, "Title", singleLine = true)
                        UploadField(description, { description = it }, "Description")

                        ChoiceChips(
                            options = listOf("MUSIC", "AUDIOBOOK", "VIDEO", "OTHER"),
                            selected = mediaType,
                            onSelect = { mediaType = it },
                        )

                        if (mode == "LINK") {
                            ChoiceChips(
                                options = listOf("AUDIO_ONLY", "VIDEO"),
                                selected = downloadType,
                                onSelect = { option ->
                                    downloadType = option
                                    mediaType = if (option == "VIDEO") "VIDEO" else "MUSIC"
                                },
                            )
                        }

                        SwitchRow("Public", public, onCheckedChange = { public = it })

                        LexButton(
                            text = when {
                                state.saving -> "Working..."
                                mode == "LINK" -> "Download & add"
                                else -> "Upload file"
                            },
                            onClick = {
                                if (mode == "LINK") {
                                    viewModel.link(url, title, description, public, mediaType, downloadType)
                                } else {
                                    uri?.let { selected ->
                                        val bytes = context.contentResolver.openInputStream(selected)?.use { it.readBytes() }
                                        if (bytes != null) {
                                            viewModel.file(
                                                bytes = bytes,
                                                filename = selected.lastPathSegment?.substringAfterLast('/') ?: "media",
                                                contentType = context.contentResolver.getType(selected),
                                                title = title,
                                                description = description,
                                                isPublic = public,
                                                type = mediaType,
                                            )
                                        }
                                    }
                                }
                            },
                            tone = LexTone.Primary,
                            enabled = !state.saving && title.isNotBlank() &&
                                ((mode == "LINK" && url.isNotBlank()) || (mode == "FILE" && uri != null)),
                            loading = state.saving,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            if (mode == "TORRENT") {
                item {
                    FormCard {
                        Text(
                            "Torrent",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "The Lexicon machine downloads this over its VPN and adds only the " +
                                ".mp3 and .mp4 files — nothing else in the torrent is downloaded. " +
                                "Deleting an added file later also removes it from the torrent.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        if (state.torrentReady == false) {
                            Text(
                                state.torrentBlockedReason.ifBlank { "Torrent downloads are unavailable." },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LexButton(
                                text = "Choose .torrent",
                                onClick = {
                                    torrentPicker.launch(
                                        arrayOf(
                                            "application/x-bittorrent",
                                            "application/octet-stream",
                                            "*/*",
                                        )
                                    )
                                },
                                icon = Icons.Filled.AttachFile,
                            )
                            // Weighted, so the filename takes what is left of the row rather than
                            // being measured at its full length and pushing the button out.
                            Text(
                                torrentUri?.lastPathSegment?.substringAfterLast('/') ?: "No file chosen",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        UploadField(
                            torrentUrl,
                            {
                                torrentUrl = it
                                if (it.isNotBlank()) torrentUri = null
                            },
                            "Or a magnet link / .torrent URL",
                        )
                        UploadField(torrentTitle, { torrentTitle = it }, "Name (optional)", singleLine = true)

                        SwitchRow("Public", torrentPublic, onCheckedChange = { torrentPublic = it })

                        LexButton(
                            text = if (state.saving) "Working..." else "Start download",
                            onClick = {
                                val chosen = torrentUri
                                if (chosen != null) {
                                    // A .torrent is a few kilobytes, so reading it whole is fine.
                                    val bytes = context.contentResolver.openInputStream(chosen)?.use { it.readBytes() }
                                    if (bytes == null) {
                                        viewModel.messageFromScreen("Could not read that file.")
                                    } else {
                                        viewModel.submitTorrentFile(
                                            bytes,
                                            chosen.lastPathSegment?.substringAfterLast('/') ?: "upload.torrent",
                                            torrentTitle.trim(),
                                            torrentPublic,
                                        )
                                        torrentUri = null
                                        torrentTitle = ""
                                    }
                                } else {
                                    viewModel.submitTorrentUrl(torrentUrl.trim(), torrentTitle.trim(), torrentPublic)
                                    torrentUrl = ""
                                    torrentTitle = ""
                                }
                            },
                            tone = LexTone.Primary,
                            enabled = !state.saving && state.torrentReady != false &&
                                (torrentUri != null || torrentUrl.isNotBlank()),
                            loading = state.saving,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                items(state.torrents, key = { it.infoHash }) { torrent ->
                    FormCard {
                        Text(
                            torrent.name.ifBlank { "Torrent" },
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        LinearProgressIndicator(
                            progress = { torrent.percent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            torrentStatusText(torrent),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        torrent.error?.takeIf { it.isNotBlank() }?.let { error ->
                            Text(
                                error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        ActionRow(horizontalAlignment = Alignment.End) {
                            LexButton(
                                text = "Remove",
                                onClick = { viewModel.removeTorrent(torrent.infoHash) },
                                tone = LexTone.Destructive,
                                icon = Icons.Filled.Delete,
                            )
                        }
                    }
                }
            }

            if (mode == "PLAYLIST") {
                item {
                    FormCard {
                        UploadField(playlistUrl, { playlistUrl = it }, "Playlist URL")
                        UploadField(
                            playlistName,
                            { playlistName = it },
                            "Playlist name (optional)",
                            singleLine = true,
                        )

                        ChoiceChips(
                            options = listOf("AUDIO_ONLY", "VIDEO"),
                            selected = playlistDownloadType,
                            onSelect = { option ->
                                playlistDownloadType = option
                                playlistMediaType = if (option == "VIDEO") "VIDEO" else "MUSIC"
                            },
                        )
                        ChoiceChips(
                            options = listOf("MUSIC", "VIDEO", "AUDIOBOOK", "OTHER"),
                            selected = playlistMediaType,
                            onSelect = { playlistMediaType = it },
                        )

                        SwitchRow("Playlist is public", playlistPublic, onCheckedChange = { playlistPublic = it })
                        SwitchRow("Imported media is public", mediaPublic, onCheckedChange = { mediaPublic = it })

                        LexButton(
                            text = if (state.saving) "Starting..." else "Import playlist",
                            onClick = {
                                viewModel.importPlaylist(
                                    url = playlistUrl,
                                    playlistName = playlistName,
                                    playlistPublic = playlistPublic,
                                    mediaPublic = mediaPublic,
                                    mediaType = playlistMediaType,
                                    downloadType = playlistDownloadType,
                                )
                            },
                            tone = LexTone.Primary,
                            enabled = !state.saving && playlistUrl.isNotBlank(),
                            loading = state.saving,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            if (mode == "BROWSE") {
                item {
                    FormCard {
                        // Two peer sources were a filled button and an outlined one, which read
                        // as "do this" and "or maybe this". Equal weights, equal emphasis.
                        SplitActions(
                            secondary = {
                                LexButton(
                                    text = "My files",
                                    onClick = viewModel::loadMyMedia,
                                    enabled = !state.loading,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            },
                            primary = {
                                LexButton(
                                    text = "Public",
                                    onClick = viewModel::loadPublicMedia,
                                    enabled = !state.loading,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            },
                        )

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                label = { Text("Search title or description", maxLines = 1) },
                                singleLine = true,
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.weight(1f),
                            )
                            LexIconButton(
                                icon = Icons.Filled.Search,
                                contentDescription = "Search",
                                onClick = {
                                    if (searchQuery.isNotBlank()) viewModel.searchMedia(searchQuery)
                                },
                                enabled = !state.loading,
                                tone = LexTone.Primary,
                            )
                        }

                        ChoiceChips(
                            options = listOf("ALL", "MUSIC", "VIDEO", "AUDIOBOOK", "OTHER"),
                            selected = filterType,
                            onSelect = { filterType = it },
                        )
                    }
                }

                if (state.loading) {
                    item { NoticeCard("Loading media...") }
                } else if (filtered.isEmpty()) {
                    item { NoticeCard("No files found for this filter.") }
                } else {
                    items(filtered, key = { it.id }) { media ->
                        Card(
                            shape = Radii.card,
                            elevation = CardDefaults.cardElevation(defaultElevation = Elevation.card),
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItem(),
                        ) {
                            Column(
                                Modifier.padding(Spacing.lg),
                                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                            ) {
                                Text(
                                    media.displayTitle,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    media.description.ifBlank { "No description" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${media.mediaType.ifBlank { "OTHER" }} · " +
                                        "${"%.1f".format(media.fileSize / 1_048_576.0)} MB",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )

                                // Download + Edit + Delete is about 300dp of fixed buttons in the
                                // 288dp a 320dp phone has, so Delete clipped. This wraps.
                                ActionRow(Modifier.padding(top = Spacing.xs)) {
                                    LexButton(
                                        text = "Download",
                                        onClick = { downloadMedia(context, media, viewModel.authToken) },
                                        tone = LexTone.Primary,
                                        icon = Icons.Filled.Download,
                                    )
                                    LexButton(
                                        text = "Edit",
                                        onClick = { editTarget = media },
                                        icon = Icons.Filled.Edit,
                                    )
                                    LexButton(
                                        text = "Delete",
                                        onClick = { viewModel.deleteMedia(media.id) },
                                        tone = LexTone.Destructive,
                                        icon = Icons.Filled.Delete,
                                        enabled = !state.saving,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            state.message?.let { message -> item { MessageCard(message) } }
        }
    }
}

/** The one card every form section on this screen sits in; there were five near-copies. */
@Composable
private fun FormCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = Radii.card,
        elevation = CardDefaults.cardElevation(defaultElevation = Elevation.card),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
            content = content,
        )
    }
}

/** A plain informational card — loading and empty states. */
@Composable
private fun NoticeCard(text: String) {
    Card(shape = Radii.card, modifier = Modifier.fillMaxWidth()) {
        Text(
            text,
            Modifier.padding(Spacing.lg),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The result banner.
 *
 * It was two hardcoded pastels — `Color(0xFFDFF2E3)` on `Color(0xFF1B5E20)` for success and
 * `Color(0xFFF9DEDC)` on `Color(0xFF7F1D1D)` for failure. Both are light-theme colours, so on a
 * near-black screen the banner arrived as a bright mint or pink slab, the only one in the app.
 */
@Composable
private fun MessageCard(message: String) {
    val failed = message.contains("error", ignoreCase = true) ||
        message.contains("failed", ignoreCase = true)
    Card(
        shape = Radii.card,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (failed) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                Status.ok.copy(alpha = 0.18f)
            },
            contentColor = if (failed) MaterialTheme.colorScheme.onErrorContainer else Status.ok,
        ),
    ) {
        Text(message, Modifier.padding(Spacing.lg), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * One row of mutually exclusive options.
 *
 * This screen built the same `FlowRow` of `FilterChip`s six times, each with its own spacing, to
 * state six different choices. The wire values are shouty constants (`AUDIO_ONLY`), so they are
 * title-cased for display in one place rather than at every call site.
 */
@Composable
private fun ChoiceChips(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    ActionRow {
        options.forEach { option ->
            LexChip(
                label = option.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() },
                selected = selected == option,
                onClick = { onSelect(option) },
            )
        }
    }
}

/** Every text field on this screen: full width, one shape, capped label. */
@Composable
private fun UploadField(
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

private fun downloadMedia(context: Context, media: MediaFile, token: String) {
    val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    val fileName = media.originalFilename.ifBlank { media.filename.ifBlank { "media_${media.id}" } }
    val request = DownloadManager.Request(Uri.parse("${ApiUrls.LEXICON}api/media/${media.id}/download"))
        .setTitle(media.displayTitle)
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
    if (token.isNotBlank()) {
        request.addRequestHeader("Authorization", "Bearer $token")
    }
    downloadManager.enqueue(request)
}

@Composable
private fun EditMediaDialog(
    media: MediaFile,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, Boolean) -> Unit,
) {
    var title by remember(media.id) { mutableStateOf(media.title.ifBlank { media.displayTitle }) }
    var description by remember(media.id) { mutableStateOf(media.description) }
    var public by remember(media.id) { mutableStateOf(media.isPublic) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = Radii.card,
        title = { Text("Edit media") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                UploadField(title, { title = it }, "Title", singleLine = true)
                UploadField(description, { description = it }, "Description")
                SwitchRow("Public", public, onCheckedChange = { public = it })
            }
        },
        confirmButton = {
            LexButton(
                text = "Save",
                onClick = { onSave(title, description, public) },
                tone = LexTone.Primary,
                enabled = !saving && title.isNotBlank(),
                loading = saving,
            )
        },
        dismissButton = { LexButton("Cancel", onDismiss, tone = LexTone.Quiet, enabled = !saving) },
    )
}

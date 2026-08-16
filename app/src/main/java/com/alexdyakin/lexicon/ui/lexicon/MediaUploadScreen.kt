package com.alexdyakin.lexicon.ui.lexicon

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexdyakin.lexicon.R
import com.alexdyakin.lexicon.data.ApiResult
import com.alexdyakin.lexicon.data.ApiUrls
import com.alexdyakin.lexicon.data.MediaFile
import com.alexdyakin.lexicon.data.MediaUpdateRequest
import com.alexdyakin.lexicon.data.TokenStore
import com.alexdyakin.lexicon.data.api.MediaApi
import com.alexdyakin.lexicon.data.api.PlaylistApi
import com.alexdyakin.lexicon.data.safeApiCall
import com.alexdyakin.lexicon.ui.components.OnScrimDim
import com.alexdyakin.lexicon.ui.components.ScreenScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject

enum class BrowseSource { NONE, MY, PUBLIC, SEARCH }

data class MediaUploadUiState(
    val saving: Boolean = false,
    val loading: Boolean = false,
    val message: String? = null,
    val mediaFiles: List<MediaFile> = emptyList(),
    val browseSource: BrowseSource = BrowseSource.NONE,
)

@HiltViewModel
class MediaUploadViewModel @Inject constructor(
    private val api: MediaApi,
    private val playlistApi: PlaylistApi,
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

    private fun loadBrowse(source: BrowseSource, call: suspend () -> List<MediaFile>) = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, message = null, browseSource = source)
        when (val result = safeApiCall { call() }) {
            is ApiResult.Success -> _state.value = _state.value.copy(loading = false, mediaFiles = result.data)
            is ApiResult.Failure -> _state.value = _state.value.copy(loading = false, message = result.message)
            ApiResult.Unauthorized -> _state.value = _state.value.copy(loading = false, message = "Sign in again to browse media.")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
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

    val filtered = remember(state.mediaFiles, filterType) {
        if (filterType == "ALL") state.mediaFiles
        else state.mediaFiles.filter { (it.mediaType.ifBlank { "OTHER" }).equals(filterType, ignoreCase = true) }
    }

    if (editTarget != null) {
        EditMediaDialog(
            media = editTarget!!,
            saving = state.saving,
            onDismiss = { editTarget = null },
            onSave = { newTitle, newDescription, newPublic ->
                viewModel.updateMedia(editTarget!!.id, newTitle.trim(), newDescription.trim(), newPublic)
                editTarget = null
            },
        )
    }

    ScreenScaffold("Add media", onBack, R.drawable.bg_dashboard) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Upload & Download", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Native Android media management. Text contrast has been tuned for readability.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("FILE", "LINK", "PLAYLIST", "BROWSE").forEach { option ->
                        FilterChip(
                            selected = mode == option,
                            onClick = {
                                mode = option
                                viewModel.clearMessage()
                            },
                            label = { Text(option.lowercase().replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }
            }

            if (mode == "FILE" || mode == "LINK") {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (mode == "FILE") {
                                Button(onClick = { picker.launch(arrayOf("audio/*", "video/*")) }) {
                                    Text(if (uri == null) "Choose audio or video" else "Selected: ${uri?.lastPathSegment ?: "file"}")
                                }
                            } else {
                                OutlinedTextField(
                                    value = url,
                                    onValueChange = { url = it },
                                    label = { Text("YouTube or media URL") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }

                            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth())

                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("MUSIC", "AUDIOBOOK", "VIDEO", "OTHER").forEach { option ->
                                    FilterChip(
                                        selected = mediaType == option,
                                        onClick = { mediaType = option },
                                        label = { Text(option.lowercase().replaceFirstChar { it.uppercase() }) },
                                    )
                                }
                            }

                            if (mode == "LINK") {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf("AUDIO_ONLY", "VIDEO").forEach { option ->
                                        FilterChip(
                                            selected = downloadType == option,
                                            onClick = {
                                                downloadType = option
                                                mediaType = if (option == "VIDEO") "VIDEO" else "MUSIC"
                                            },
                                            label = { Text(option.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }) },
                                        )
                                    }
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Public", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                                Switch(checked = public, onCheckedChange = { public = it })
                            }

                            Button(
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
                                enabled = !state.saving && title.isNotBlank() && ((mode == "LINK" && url.isNotBlank()) || (mode == "FILE" && uri != null)),
                            ) {
                                Text(if (state.saving) "Working..." else if (mode == "LINK") "Download & add" else "Upload file")
                            }
                        }
                    }
                }
            }

            if (mode == "PLAYLIST") {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(value = playlistUrl, onValueChange = { playlistUrl = it }, label = { Text("Playlist URL") }, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(value = playlistName, onValueChange = { playlistName = it }, label = { Text("Playlist name (optional)") }, modifier = Modifier.fillMaxWidth())

                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("AUDIO_ONLY", "VIDEO").forEach { option ->
                                    FilterChip(
                                        selected = playlistDownloadType == option,
                                        onClick = {
                                            playlistDownloadType = option
                                            playlistMediaType = if (option == "VIDEO") "VIDEO" else "MUSIC"
                                        },
                                        label = { Text(option.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }) },
                                    )
                                }
                            }

                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("MUSIC", "VIDEO", "AUDIOBOOK", "OTHER").forEach { option ->
                                    FilterChip(
                                        selected = playlistMediaType == option,
                                        onClick = { playlistMediaType = option },
                                        label = { Text(option.lowercase().replaceFirstChar { it.uppercase() }) },
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Playlist is public", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                                Switch(checked = playlistPublic, onCheckedChange = { playlistPublic = it })
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Imported media is public", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                                Switch(checked = mediaPublic, onCheckedChange = { mediaPublic = it })
                            }

                            Button(
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
                                enabled = !state.saving && playlistUrl.isNotBlank(),
                            ) {
                                Text(if (state.saving) "Starting..." else "Import playlist")
                            }
                        }
                    }
                }
            }

            if (mode == "BROWSE") {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = viewModel::loadMyMedia, enabled = !state.loading) { Text("My files") }
                                OutlinedButton(onClick = viewModel::loadPublicMedia, enabled = !state.loading) { Text("Public") }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    label = { Text("Search title or description") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                )
                                Button(onClick = { if (searchQuery.isNotBlank()) viewModel.searchMedia(searchQuery) }, enabled = !state.loading) { Text("Search") }
                            }

                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("ALL", "MUSIC", "VIDEO", "AUDIOBOOK", "OTHER").forEach { option ->
                                    FilterChip(selected = filterType == option, onClick = { filterType = option }, label = { Text(option) })
                                }
                            }
                        }
                    }
                }

                if (state.loading) {
                    item {
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))) {
                            Text("Loading media...", modifier = Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else if (filtered.isEmpty()) {
                    item {
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))) {
                            Text("No files found for this filter.", modifier = Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    items(filtered, key = { it.id }) { media ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItem(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)),
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(media.displayTitle, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(media.description.ifBlank { "No description" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${media.mediaType.ifBlank { "OTHER" }}  ·  ${"%.1f".format(media.fileSize / 1_048_576.0)} MB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { downloadMedia(context, media, viewModel.authToken) }) { Text("Download") }
                                    OutlinedButton(onClick = { editTarget = media }) { Text("Edit") }
                                    OutlinedButton(onClick = { viewModel.deleteMedia(media.id) }, enabled = !state.saving) { Text("Delete") }
                                }
                            }
                        }
                    }
                }
            }

            state.message?.let { message ->
                item {
                    val success = !message.contains("error", ignoreCase = true) && !message.contains("failed", ignoreCase = true)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (success) Color(0xFFDFF2E3) else Color(0xFFF9DEDC),
                            contentColor = if (success) Color(0xFF1B5E20) else Color(0xFF7F1D1D),
                        ),
                    ) {
                        Text(message, modifier = Modifier.padding(14.dp))
                    }
                }
            }

            item {
                Box(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text("Default media type: Music", color = OnScrimDim, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
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
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit media") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, singleLine = true)
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Public", Modifier.weight(1f))
                    Switch(checked = public, onCheckedChange = { public = it })
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(title, description, public) }, enabled = !saving && title.isNotBlank()) { Text("Save") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") } },
    )
}

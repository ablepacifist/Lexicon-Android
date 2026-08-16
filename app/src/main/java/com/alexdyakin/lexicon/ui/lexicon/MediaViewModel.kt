package com.alexdyakin.lexicon.ui.lexicon

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexdyakin.lexicon.data.ApiResult
import com.alexdyakin.lexicon.data.MediaFile
import com.alexdyakin.lexicon.data.MediaKind
import com.alexdyakin.lexicon.data.MediaUpdateRequest
import com.alexdyakin.lexicon.data.PlaybackPositionRequest
import com.alexdyakin.lexicon.data.TokenStore
import com.alexdyakin.lexicon.data.api.MediaApi
import com.alexdyakin.lexicon.data.api.PlaybackApi
import com.alexdyakin.lexicon.data.safeApiCall
import com.alexdyakin.lexicon.media.PlayerConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class MediaFilter(val label: String) {
    ALL("All"), MUSIC("Music"), AUDIOBOOKS("Books"), VIDEO("Video");
}

data class MediaUiState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    val all: List<MediaFile> = emptyList(),
    val currentUserId: Int = -1,
    val filter: MediaFilter = MediaFilter.ALL,
    val query: String = "",
    val error: String? = null,
) {
    /** Filtering and search are local — the library is small enough and it feels instant. */
    val visible: List<MediaFile>
        get() = all
            .filter { m ->
                when (filter) {
                    MediaFilter.ALL -> true
                    MediaFilter.MUSIC -> m.kind == MediaKind.AUDIO
                    MediaFilter.AUDIOBOOKS -> m.kind == MediaKind.AUDIOBOOK
                    MediaFilter.VIDEO -> m.kind == MediaKind.VIDEO
                }
            }
            .filter { m -> query.isBlank() || m.displayTitle.contains(query, ignoreCase = true) }
}

@HiltViewModel
class MediaViewModel @Inject constructor(
    private val mediaApi: MediaApi,
    private val playbackApi: PlaybackApi,
    private val tokenStore: TokenStore,
    val player: PlayerConnection,
    @ApplicationContext context: Context,
) : ViewModel() {

    private val playbackPrefs = context.getSharedPreferences("media_playback", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(MediaUiState(currentUserId = tokenStore.userId))
    val state: StateFlow<MediaUiState> = _state.asStateFlow()

    init {
        player.setSpeed(playbackPrefs.getFloat(KEY_AUDIOBOOK_SPEED, 1f))
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            when (val result = safeApiCall { mediaApi.recent(200) }) {
                is ApiResult.Success ->
                    _state.value = _state.value.copy(loading = false, all = result.data)
                is ApiResult.Unauthorized ->
                    _state.value = _state.value.copy(loading = false, error = "Session expired. Sign in again.")
                is ApiResult.Failure ->
                    _state.value = _state.value.copy(loading = false, error = result.message)
            }
        }
    }

    fun setFilter(filter: MediaFilter) { _state.value = _state.value.copy(filter = filter) }
    fun setQuery(query: String) { _state.value = _state.value.copy(query = query) }

    fun updateMedia(mediaId: Int, title: String, description: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(saving = true, error = null)
            when (val result = safeApiCall {
                mediaApi.update(mediaId, tokenStore.userId, MediaUpdateRequest(title = title.trim(), description = description.trim()))
            }) {
                is ApiResult.Success -> refresh()
                is ApiResult.Failure -> _state.value = _state.value.copy(saving = false, error = result.message)
                ApiResult.Unauthorized -> _state.value = _state.value.copy(saving = false, error = "Only the uploader can edit this media file.")
            }
        }
    }

    fun deleteMedia(mediaId: Int) {
        viewModelScope.launch {
            _state.value = _state.value.copy(saving = true, error = null)
            when (val result = safeApiCall { mediaApi.delete(mediaId, tokenStore.userId) }) {
                is ApiResult.Success -> refresh()
                is ApiResult.Failure -> _state.value = _state.value.copy(saving = false, error = result.message)
                ApiResult.Unauthorized -> _state.value = _state.value.copy(saving = false, error = "Only the uploader can delete this media file.")
            }
        }
    }

    /** Plays the tapped item with the rest of the visible list as its queue. */
    fun play(item: MediaFile) {
        viewModelScope.launch {
            val queue = _state.value.visible.filter { it.isPlayable }
            val index = queue.indexOfFirst { it.id == item.id }
            if (index < 0) return@launch

            val resumeMs = if (item.kind == MediaKind.AUDIOBOOK) loadResumePositionMs(item.id) else 0L
            player.play(queue, index, resumeMs)
            if (item.kind == MediaKind.AUDIOBOOK) {
                player.setSpeed(playbackPrefs.getFloat(KEY_AUDIOBOOK_SPEED, 1f))
            }
        }
    }

    fun setSpeed(speed: Float) {
        player.setSpeed(speed)
        playbackPrefs.edit().putFloat(KEY_AUDIOBOOK_SPEED, speed).apply()
    }

    private suspend fun loadResumePositionMs(mediaFileId: Int): Long {
        if (tokenStore.userId <= 0) return 0L
        return when (val result = safeApiCall { playbackApi.position(tokenStore.userId, mediaFileId) }) {
            is ApiResult.Success -> result.data
                .takeIf { it.found && !it.completed && it.position > 0.0 }
                ?.position?.times(1_000)?.toLong() ?: 0L
            else -> 0L
        }
    }

    private companion object {
        const val KEY_AUDIOBOOK_SPEED = "audiobook_speed"
    }
}

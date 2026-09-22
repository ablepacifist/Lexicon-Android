package com.alexdyakin.lexicon.lexi

import com.alexdyakin.lexicon.data.ApiResult
import com.alexdyakin.lexicon.data.LiveStreamState
import com.alexdyakin.lexicon.data.MediaFile
import com.alexdyakin.lexicon.data.SkipStreamRequest
import com.alexdyakin.lexicon.data.TokenStore
import com.alexdyakin.lexicon.data.api.LiveStreamApi
import com.alexdyakin.lexicon.data.api.MediaApi
import com.alexdyakin.lexicon.data.api.PlaylistApi
import com.alexdyakin.lexicon.data.safeApiCall
import com.alexdyakin.lexicon.media.PlayerConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lexi's view of the Lexicon library, over the same APIs the screens use.
 *
 * Failures come back as [IOException] rather than [ApiResult], because a voice turn has one
 * thing to say about all of them ("I couldn't reach the music library") and nowhere to show
 * a retry button.
 */
@Singleton
class LexiconVoiceLibrary @Inject constructor(
    private val mediaApi: MediaApi,
    private val playlistApi: PlaylistApi,
    private val liveStreamApi: LiveStreamApi,
    private val tokenStore: TokenStore,
) : VoiceLibrary {

    override val userId: Int? get() = tokenStore.userId.takeIf { it > 0 }

    override suspend fun search(query: String): List<MediaFile> = call { mediaApi.search(query) }

    override suspend fun mediaById(id: Int): MediaFile? =
        safeApiCall { mediaApi.byId(id) }.successOrNull

    override suspend fun ownedMedia(userId: Int): List<MediaFile> = call { mediaApi.byUser(userId) }

    override suspend fun playlists(userId: Int): List<NamedPlaylist> {
        val own = call { playlistApi.byUser(userId) }
        // Public playlists too: a shared one is just as nameable out loud as your own.
        val shared = safeApiCall { playlistApi.public() }.successOrNull.orEmpty()
        return (own + shared).distinctBy { it.id }.map { NamedPlaylist(it.id, it.name) }
    }

    override suspend fun playlistTracks(playlistId: Int): List<MediaFile> {
        val playlist = call { playlistApi.byId(playlistId) }
        // Playlist items are wrappers, not media files: the track is `mediaFile`, and its id
        // is `mediaFileId`. The server's `itemCount` and `mediaFileIds` are unreliable here —
        // the bug that made playlists play nothing on the Pi.
        return playlist.items.mapNotNull { item ->
            item.mediaFile.takeIf { it.id > 0 }
                ?: item.mediaFileId.takeIf { it > 0 }?.let { mediaById(it) }
        }
    }

    override suspend fun liveStreamState(): LiveStreamState = call { liveStreamApi.state(MUSIC_CHANNEL) }.state

    override suspend fun voteToSkipLiveStream(request: SkipStreamRequest) =
        call { liveStreamApi.skip(MUSIC_CHANNEL, request) }

    private suspend fun <T> call(block: suspend () -> T): T = when (val result = safeApiCall(block)) {
        is ApiResult.Success -> result.data
        is ApiResult.Unauthorized -> throw IOException("Lexicon rejected the sign-in")
        is ApiResult.Failure -> throw IOException(result.message)
    }

    private companion object {
        /** Lexi joins the music stream; the video one has nothing to listen to. */
        const val MUSIC_CHANNEL = "music"
    }
}

/**
 * Lexi's view of the player: the app's own background player, so a voice request behaves
 * exactly like tapping play — same queue, same notification, same lock-screen controls.
 */
@Singleton
class LexiVoicePlayback @Inject constructor(
    private val player: PlayerConnection,
) : VoicePlayback {

    override val isPlaying: Boolean get() = player.state.value.isPlaying

    // Media3's controller may only be touched from the thread it was built on, and silently
    // drops calls from any other — no crash, no log, nothing plays. A voice turn runs on a
    // background thread, so every call hops to the main thread first.
    override suspend fun play(tracks: List<MediaFile>, shuffle: Boolean, startPositionMs: Long) =
        withContext(Dispatchers.Main) {
            player.setShuffle(shuffle)
            // Shuffle should also change which track comes first; ExoPlayer would otherwise
            // always begin with the one at the start index and shuffle only what follows.
            val start = if (shuffle && tracks.isNotEmpty()) tracks.indices.random() else 0
            player.play(tracks, start, startPositionMs)
        }

    override suspend fun next() = withContext(Dispatchers.Main) { player.next() }

    override suspend fun stop() = withContext(Dispatchers.Main) { player.stop() }
}

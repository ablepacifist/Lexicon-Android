package com.alexdyakin.lexicon.media

import android.content.ComponentName
import android.content.Context
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.alexdyakin.lexicon.data.ApiUrls
import com.alexdyakin.lexicon.data.MediaFile
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Everything the UI needs to render a player, mirrored out of the MediaController. */
data class PlayerState(
    val connected: Boolean = false,
    val mediaId: String? = null,
    val title: String = "",
    val subtitle: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val speed: Float = 1f,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val isVideo: Boolean = false,
    val isAudiobook: Boolean = false,
    val volume: Float = 1f,
    val autoAdvance: Boolean = true,
) {
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/**
 * Single app-wide connection to [PlaybackService].
 *
 * Screens observe [state] and call the transport methods; nobody touches ExoPlayer
 * directly. Because the service owns the player, playback survives navigation,
 * backgrounding and screen-off.
 */
@Singleton
class PlayerConnection @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    /**
     * Emits when the current item reaches `STATE_ENDED`. The live stream screens use this to
     * report `media-ended` to the server, which is what advances the shared queue — the web
     * client does the same from the `<audio onEnded>` handler.
     */
    private val _trackEnded = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val trackEnded: SharedFlow<Unit> = _trackEnded.asSharedFlow()

    private var controller: MediaController? = null
    private var autoAdvance: Boolean = true

    /** A play() that arrived before the controller finished binding; replayed on connect. */
    private var pendingPlay: (() -> Unit)? = null

    init {
        connect()
        // The controller has no position callback — poll while something is playing
        scope.launch {
            while (true) {
                controller?.let { c -> if (c.isPlaying) publish(c) }
                delay(500)
            }
        }
    }

    private fun connect() {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val c = try { future.get() } catch (e: Exception) { null } ?: return@addListener
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = publish(player)

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if (!autoAdvance && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                        controller?.pause()
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) _trackEnded.tryEmit(Unit)
                }
            })
            publish(c)
            // Anything that asked to play while we were still binding runs now.
            pendingPlay?.let { start ->
                pendingPlay = null
                start()
            }
        }, MoreExecutors.directExecutor())
    }

    private fun publish(p: Player) {
        val md = p.mediaMetadata
        _state.value = PlayerState(
            connected = true,
            mediaId = p.currentMediaItem?.mediaId,
            title = md.title?.toString().orEmpty(),
            subtitle = md.artist?.toString().orEmpty(),
            isPlaying = p.isPlaying,
            positionMs = p.currentPosition.coerceAtLeast(0),
            durationMs = p.duration.takeIf { it > 0 } ?: 0,
            shuffle = p.shuffleModeEnabled,
            speed = p.playbackParameters.speed,
            hasNext = p.hasNextMediaItem(),
            hasPrevious = p.hasPreviousMediaItem(),
            isVideo = md.mediaType == MediaMetadata.MEDIA_TYPE_VIDEO,
            isAudiobook = md.albumTitle?.toString() == com.alexdyakin.lexicon.data.MediaKind.AUDIOBOOK.name,
            volume = p.volume,
            autoAdvance = autoAdvance,
        )
    }

    // ── Transport ────────────────────────────────────────────────────────────

    /**
     * Runs [block] on the main thread.
     *
     * **Load-bearing.** A Media3 `MediaController` may only be used on the thread it was built
     * on — ours is the main thread. Calls from any other thread are silently dropped: no crash,
     * no log, nothing plays. That is exactly how the live stream failed, because its SSE
     * handler runs on an OkHttp callback thread and called play() from there. Every controller
     * touch goes through here so no caller has to know the rule.
     */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else scope.launch { block() }
    }

    /** Play [queue], starting at [startIndex]. Shuffle/next/prev come free from ExoPlayer. */
    // Explicit Unit: the body references play() via pendingPlay, which otherwise makes the
    // return type recursive and fails inference.
    fun play(queue: List<MediaFile>, startIndex: Int, startPositionMs: Long = 0L): Unit = onMain {
        val items = queue.map { it.toMediaItem() }
        if (items.isNotEmpty()) {
            val c = controller
            if (c == null) {
                // The controller binds to PlaybackService asynchronously, so on a cold start it
                // is still null for the first few hundred ms. Replay once connected instead of
                // dropping the request.
                pendingPlay = { play(queue, startIndex, startPositionMs) }
            } else {
                pendingPlay = null
                c.setMediaItems(items, startIndex.coerceIn(0, items.lastIndex), startPositionMs.coerceAtLeast(0L))
                c.prepare()
                c.play()
            }
        }
    }

    fun togglePlayPause() = onMain {
        val c = controller ?: return@onMain
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() = onMain { controller?.seekToNextMediaItem() }
    fun previous() = onMain { controller?.seekToPreviousMediaItem() }
    fun seekTo(ms: Long) = onMain { controller?.seekTo(ms) }

    fun seekBy(deltaMs: Long) = onMain {
        val c = controller ?: return@onMain
        val duration = c.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = (c.currentPosition + deltaMs).coerceIn(0L, duration)
        c.seekTo(target)
    }

    fun seekToFraction(f: Float) = onMain {
        val c = controller ?: return@onMain
        val d = c.duration
        if (d > 0) c.seekTo((d * f.coerceIn(0f, 1f)).toLong())
    }

    fun toggleShuffle() = onMain {
        val c = controller ?: return@onMain
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }

    fun setShuffle(enabled: Boolean) = onMain {
        controller?.shuffleModeEnabled = enabled
    }

    fun setVolume(volume: Float) {
        val c = controller ?: return
        c.volume = volume.coerceIn(0f, 1f)
        publish(c)
    }

    fun setAutoAdvance(enabled: Boolean) {
        autoAdvance = enabled
        controller?.let { publish(it) }
    }

    fun setSpeed(speed: Float) {
        controller?.setPlaybackSpeed(speed.coerceIn(0.5f, 3f))
    }

    fun stop() {
        controller?.run { pause(); clearMediaItems() }
    }

    fun currentPositionMs(): Long = controller?.currentPosition ?: 0
    fun currentDurationMs(): Long = controller?.duration?.takeIf { it > 0 } ?: 0

    /** Used exclusively by Media3's native [androidx.media3.ui.PlayerView]. */
    fun playerOrNull(): Player? = controller
}

/** Streams straight from the Lexicon API; the bearer is added by AuthInterceptor. */
fun MediaFile.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id.toString())
    .setUri(ApiUrls.LEXICON + "api/media/stream/$id")
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(displayTitle)
            .setArtist(description.ifBlank { mediaType.lowercase().ifBlank { "Lexicon" } })
            .setAlbumTitle(kind.name)
            .setMediaType(if (kind == com.alexdyakin.lexicon.data.MediaKind.VIDEO)
                MediaMetadata.MEDIA_TYPE_VIDEO else MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()
    )
    .build()

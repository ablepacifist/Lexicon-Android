package com.alexdyakin.lexicon.media

import com.alexdyakin.lexicon.data.PlaybackPositionRequest
import com.alexdyakin.lexicon.data.TokenStore
import com.alexdyakin.lexicon.data.api.PlaybackApi
import com.alexdyakin.lexicon.data.safeApiCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists audiobook progress independently of any Compose destination.
 *
 * The singleton stays alive while the process does, so an audiobook continues to save after
 * its library screen has left the navigation back stack. Video and music are deliberately
 * excluded: audiobook progress is the only media position the product persists.
 */
@Singleton
class AudiobookPlaybackPositionTracker @Inject constructor(
    private val player: PlayerConnection,
    private val playbackApi: PlaybackApi,
    private val tokenStore: TokenStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastObserved = player.state.value
    private var lastSavedAtMs = 0L

    init {
        scope.launch {
            player.state.collect { current ->
                val previous = lastObserved
                if (previous.mediaId != current.mediaId || previous.isPlaying && !current.isPlaying) {
                    save(previous, force = true)
                }
                lastObserved = current
            }
        }
        scope.launch {
            while (true) {
                delay(SAVE_INTERVAL_MS)
                save(player.state.value)
            }
        }
    }

    private fun save(state: PlayerState, force: Boolean = false) {
        if (!state.isAudiobook || tokenStore.userId <= 0 || state.durationMs <= 0) return
        val mediaFileId = state.mediaId?.toIntOrNull() ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastSavedAtMs < MIN_SAVE_GAP_MS) return
        lastSavedAtMs = now

        val position = state.positionMs / 1_000.0
        val duration = state.durationMs / 1_000.0
        scope.launch {
            // A failed tracking request must never stop the player or surface as a playback error.
            safeApiCall {
                playbackApi.savePosition(
                    PlaybackPositionRequest(
                        userId = tokenStore.userId,
                        mediaFileId = mediaFileId,
                        position = position,
                        duration = duration,
                        completed = duration - position < COMPLETION_REMAINING_SECONDS,
                    )
                )
            }
        }
    }

    private companion object {
        const val SAVE_INTERVAL_MS = 15_000L
        const val MIN_SAVE_GAP_MS = 1_000L
        const val COMPLETION_REMAINING_SECONDS = 30.0
    }
}
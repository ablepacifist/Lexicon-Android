package com.alexdyakin.lexicon.lexi

import android.util.Log
import com.alexdyakin.lexicon.data.LiveStreamState
import com.alexdyakin.lexicon.data.MediaFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Keeps the phone on whatever the communal stream is playing. */
interface LiveStreamFollowing {
    /** Begins following; [onTrack] is called with each track the stream moves to. */
    fun start(onTrack: suspend (MediaFile) -> Unit)
    fun stop()
}

/**
 * Follows Lexicon's live stream over its server-sent-events feed, so a voice "join the live
 * stream" keeps up when someone else skips, instead of playing one track and falling behind.
 *
 * Deliberately listen-only: it never reports a track as finished. Both the live stream screen
 * and the Pi already do that, and a second reporter would advance the shared queue twice. The
 * consequence is that when the phone is the *only* listener, the stream stops at the end of
 * the current track.
 */
class LiveStreamFollower(
    private val client: OkHttpClient,
    private val json: Json,
    private val updatesUrl: String,
    /** Resolves a track id to the file to play; the feed often carries only the id. */
    private val resolveTrack: suspend (Int) -> MediaFile?,
    private val scope: CoroutineScope,
) : LiveStreamFollowing {

    private var job: Job? = null

    /** The in-flight request. Cancelling it is the only way to unblock the reader. */
    @Volatile private var call: Call? = null

    override fun start(onTrack: suspend (MediaFile) -> Unit) {
        stop()
        job = scope.launch {
            // The reader blocks on the socket, so track changes cross to this coroutine
            // through a channel. CONFLATED: only the newest track matters if we fall behind.
            val trackIds = Channel<Int>(Channel.CONFLATED)
            val reader = launch(Dispatchers.IO) { read(trackIds) }
            var playing = -1
            try {
                for (id in trackIds) {
                    if (id == playing) continue
                    val track = resolveTrack(id) ?: continue
                    playing = id
                    onTrack(track)
                }
            } finally {
                reader.cancel()
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
        call?.cancel()
        call = null
    }

    private suspend fun read(trackIds: Channel<Int>) {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            try {
                val request = client.newCall(Request.Builder().url(updatesUrl).build())
                call = request
                request.execute().use { response ->
                    if (!response.isSuccessful) throw IOException("live stream feed ${response.code}")
                    attempt = 0
                    val body = response.body ?: throw IOException("live stream feed closed")
                    parseSse(body.source()) { event, data ->
                        currentMediaId(event, data)?.let(trackIds::trySend)
                    }
                }
            } catch (e: IOException) {
                if (!currentCoroutineContext().isActive) return
                Log.d(TAG, "live stream feed dropped: ${e.message}")
            }
            // The web's backoff: double up to 30s, and never give up.
            delay(minOf(1_000L shl minOf(attempt++, 5), 30_000L))
        }
    }

    /** The feed carries the state under `state` on connect and under `data` on updates. */
    private fun currentMediaId(event: String, data: String): Int? {
        val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return null
        val payload: JsonElement = when (event) {
            "init" -> root["state"]
            "state-update" -> root["data"]
            else -> null
        } ?: return null
        val state = runCatching {
            json.decodeFromJsonElement(LiveStreamState.serializer(), payload)
        }.getOrNull() ?: return null
        return state.currentMediaId.takeIf { it > 0 }
    }

    private companion object {
        const val TAG = "LexiLiveStream"
    }
}

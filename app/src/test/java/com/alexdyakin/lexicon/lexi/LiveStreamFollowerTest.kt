package com.alexdyakin.lexicon.lexi

import com.alexdyakin.lexicon.data.MediaFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Following the communal stream, against a real SSE feed.
 *
 * runBlocking rather than runTest: this is sockets and reconnect delays, and virtual time
 * would race past them while nothing real had happened.
 */
class LiveStreamFollowerTest {

    private lateinit var server: MockWebServer
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var follower: LiveStreamFollower? = null

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        follower?.stop()
        scope.cancel()
        server.shutdown()
    }

    private fun sse(vararg blocks: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(blocks.joinToString(""))

    private fun state(event: String, key: String, mediaId: Int) =
        "event: $event\ndata: {\"$key\":{\"channel\":\"music\",\"currentMediaId\":$mediaId}}\n\n"

    private fun start(): Channel<Int> {
        val played = Channel<Int>(Channel.UNLIMITED)
        follower = LiveStreamFollower(
            client = client,
            json = Json { ignoreUnknownKeys = true },
            updatesUrl = server.url("/api/livestream/updates?channel=music").toString(),
            resolveTrack = { id -> MediaFile(id = id, title = "Track $id", mediaType = "MUSIC") },
            scope = scope,
        ).also { it.start { track -> played.send(track.id) } }
        return played
    }

    @Test
    fun `plays the current track, then whatever the room moves to`() = runBlocking {
        server.enqueue(
            sse(
                ": keepalive\n\n",
                state("init", "state", 11),
                state("state-update", "data", 12),
            ),
        )
        val played = start()

        withTimeout(10_000) {
            assertEquals(11, played.receive())
            assertEquals(12, played.receive())
        }
    }

    @Test
    fun `the same track twice does not restart it`() = runBlocking {
        // The feed repeats state on every queue change, and restarting the current track
        // mid-song would be audible.
        server.enqueue(sse(state("init", "state", 11), state("state-update", "data", 11)))
        val played = start()

        withTimeout(10_000) { assertEquals(11, played.receive()) }
        assertNull(withTimeoutOrNull(1_000) { played.receive() })
    }

    @Test
    fun `reconnects after the feed drops`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(sse(state("init", "state", 20)))
        val played = start()

        // First attempt fails, then it waits a second and tries again.
        withTimeout(15_000) { assertEquals(20, played.receive()) }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `stop ends the following`() = runBlocking {
        server.enqueue(sse(state("init", "state", 11)))
        val played = start()
        withTimeout(10_000) { assertEquals(11, played.receive()) }

        follower?.stop()
        // Nothing more is served, and nothing more is played.
        server.enqueue(sse(state("state-update", "data", 12)))
        assertNull(withTimeoutOrNull(1_500) { played.receive() })
    }

    @Test
    fun `a track that cannot be resolved is skipped rather than played blindly`() = runBlocking {
        val played = Channel<Int>(Channel.UNLIMITED)
        server.enqueue(sse(state("init", "state", 11), state("state-update", "data", 12)))
        follower = LiveStreamFollower(
            client = client,
            json = Json { ignoreUnknownKeys = true },
            updatesUrl = server.url("/api/livestream/updates").toString(),
            // The first id is unknown to the library; the second resolves.
            resolveTrack = { id -> if (id == 12) MediaFile(id = id, title = "Track $id") else null },
            scope = scope,
        ).also { it.start { track -> played.send(track.id) } }

        withTimeout(10_000) { assertEquals(12, played.receive()) }
    }
}

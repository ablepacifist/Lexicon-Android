package com.alexdyakin.lexicon.data.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/**
 * Exercises the transport against a real WebSocket served by MockWebServer, so the
 * handshake rules are verified rather than assumed.
 *
 * These run on the JVM: [BridgeConnection] takes a [BridgeCredentials] rather than
 * TokenStore + AuthApi precisely so the token source can be faked here.
 *
 * runBlocking rather than runTest on purpose — the socket work happens on OkHttp
 * threads, and runTest advances only virtual time, so every timeout would fire
 * instantly while nothing real had progressed.
 */
class BridgeConnectionTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    /** Frames the server received from the client, in order. */
    private val received = LinkedBlockingDeque<String>()

    /** The server side of the socket, once a client connects. */
    @Volatile
    private var serverSocket: WebSocket? = null

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private class FakeCredentials(
        override val isSignedIn: Boolean = true,
        private val token: String = "tok-1",
        private val failMint: Boolean = false,
    ) : BridgeCredentials {
        var mintCount = 0
            private set

        override suspend fun mintSsoToken(): String {
            mintCount += 1
            if (failMint) error("lexicon unreachable")
            return token
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    /** Queues one websocket upgrade whose server side records and can push frames. */
    private fun enqueueSocket(onOpen: (WebSocket) -> Unit = {}) {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    serverSocket = webSocket
                    onOpen(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    received.add(text)
                }
            }),
        )
    }

    private fun connection(
        credentials: BridgeCredentials = FakeCredentials(),
    ) = BridgeConnection(client = client, credentials = credentials, json = json).apply {
        endpoint = server.url("/").toString().replace("http://", "ws://")
    }

    private fun authOk() =
        """{"type":"auth_ok","username":"alex","userId":7,"isAdmin":false,"sso":true}"""

    @Test
    fun `handshake sends sso_auth and reaches Ready`() = runBlocking {
        enqueueSocket { socket ->
            // Mirror the real bridge: server_state arrives before anything is asked for.
            socket.send("""{"type":"server_state","channels":[],"users":[]}""")
        }
        val conn = connection()
        conn.start()

        // The client must speak first; Ready cannot arrive until we answer it.
        val first = received.poll(5, TimeUnit.SECONDS)
        assertNotNull("client must send a frame after the socket opens", first)
        assertTrue("first frame must be sso_auth, was $first", first!!.contains("\"type\":\"sso_auth\""))
        assertTrue(first.contains("tok-1"))

        serverSocket!!.send(authOk())
        val state = withTimeout(5_000) { conn.state.first { it is BridgeState.Ready } } as BridgeState.Ready
        assertEquals("alex", state.username)
        assertEquals(7, state.userId)
        conn.stop()
    }

    /**
     * The rule the whole transport exists to enforce. The bridge dispatches handlers
     * without awaiting them, so a frame written before auth_ok is dropped or errors.
     */
    @Test
    fun `send does not reach the wire until auth_ok arrives`() = runBlocking {
        enqueueSocket()
        val conn = connection()
        conn.start()

        // Drain the handshake frame.
        val handshake = received.poll(5, TimeUnit.SECONDS)
        assertTrue(handshake!!.contains("sso_auth"))

        // Ask to send while still Authenticating.
        // A real dispatcher, not the test scheduler: the socket work happens on
        // OkHttp threads, so virtual time would not advance it.
        val sender = CoroutineScope(Dispatchers.IO).async {
            conn.send(JoinChannelCommand(3), timeoutMs = 5_000)
        }

        // Nothing may appear on the wire yet.
        assertEquals(
            "join_channel escaped before auth_ok",
            null,
            received.poll(500, TimeUnit.MILLISECONDS),
        )

        serverSocket!!.send(authOk())

        val sent = withTimeout(5_000) { sender.await() }
        assertTrue("send should report success once ready", sent)
        val join = received.poll(5, TimeUnit.SECONDS)
        assertNotNull("join_channel should arrive after auth_ok", join)
        assertTrue(join!!.contains("\"type\":\"join_channel\""))
        assertTrue(join.contains("\"channelId\":3"))
        conn.stop()
    }

    @Test
    fun `send reports failure when readiness never comes`() = runBlocking {
        enqueueSocket()
        val conn = connection()
        conn.start()
        received.poll(5, TimeUnit.SECONDS)

        // Never send auth_ok. The send must give up rather than hang forever.
        val sent = conn.send(JoinChannelCommand(1), timeoutMs = 300)
        assertFalse(sent)
        conn.stop()
    }

    @Test
    fun `auth_error moves to Failed`() = runBlocking {
        enqueueSocket()
        val conn = connection()
        conn.start()
        received.poll(5, TimeUnit.SECONDS)

        serverSocket!!.send("""{"type":"auth_error","message":"Invalid or expired SSO token"}""")

        val state = withTimeout(5_000) { conn.state.first { it is BridgeState.Failed } } as BridgeState.Failed
        assertEquals("Invalid or expired SSO token", state.reason)
        conn.stop()
    }

    /**
     * A single unmodelled frame must not take the socket down — the bridge has around
     * forty message types and adds more.
     */
    @Test
    fun `an unknown frame is surfaced without killing the connection`() = runBlocking {
        enqueueSocket()
        val conn = connection()
        conn.start()
        received.poll(5, TimeUnit.SECONDS)
        serverSocket!!.send(authOk())
        withTimeout(5_000) { conn.state.first { it is BridgeState.Ready } }

        serverSocket!!.send("""{"type":"brand_new_thing","x":1}""")
        serverSocket!!.send("""not json at all""")

        // Still usable afterwards.
        val sent = conn.send(JoinChannelCommand(9), timeoutMs = 2_000)
        assertTrue("connection should survive junk frames", sent)
        conn.stop()
    }

    @Test
    fun `a signed-out user fails permanently without minting a token`() = runBlocking {
        val creds = FakeCredentials(isSignedIn = false)
        val conn = connection(creds)
        conn.start()

        val state = withTimeout(5_000) { conn.state.first { it is BridgeState.Failed } } as BridgeState.Failed
        assertTrue(state.permanent)
        assertEquals(0, creds.mintCount)
        conn.stop()
    }

    /**
     * The token is single-use with a sixty second life, so it must be minted per
     * attempt rather than once and reused across reconnects.
     */
    @Test
    fun `each connect attempt mints a fresh token`() = runBlocking {
        enqueueSocket()
        enqueueSocket()
        val creds = FakeCredentials()
        val conn = connection(creds)
        conn.start()

        received.poll(5, TimeUnit.SECONDS)
        assertEquals(1, creds.mintCount)

        // Drop the socket; the client should reconnect and mint again.
        serverSocket!!.close(1001, "going away")

        val second = received.poll(10, TimeUnit.SECONDS)
        assertNotNull("expected a second handshake after reconnect", second)
        assertTrue(second!!.contains("sso_auth"))
        assertEquals(2, creds.mintCount)
        conn.stop()
    }

    // ── the session token the bridge issues on auth_ok (needed for its HTTP routes) ──

    @Test
    fun `the session token is exposed while Ready and gone once stopped`() = runBlocking {
        enqueueSocket()
        val conn = connection()
        conn.start()
        assertNotNull(received.poll(5, TimeUnit.SECONDS))
        assertEquals("no token before sign-in completes", null, conn.sessionToken)

        serverSocket!!.send(
            """{"type":"auth_ok","username":"alex","userId":7,"isAdmin":false,"sso":true,"verified":true,"sessionToken":"sess-abc"}""",
        )
        withTimeout(5_000) { conn.state.first { it is BridgeState.Ready } }
        assertEquals("sess-abc", conn.sessionToken)

        conn.stop()
        assertEquals("a dropped connection's token must not be used", null, conn.sessionToken)
    }

    @Test
    fun `an auth_ok from an older bridge without a token leaves no token`() = runBlocking {
        enqueueSocket()
        val conn = connection()
        conn.start()
        assertNotNull(received.poll(5, TimeUnit.SECONDS))
        serverSocket!!.send(authOk())
        withTimeout(5_000) { conn.state.first { it is BridgeState.Ready } }
        assertEquals(null, conn.sessionToken)
        conn.stop()
    }

    @Test
    fun `channels sent after auth_ok still arrive, as the current bridge orders them`() = runBlocking {
        enqueueSocket()
        val conn = connection()
        conn.start()
        assertNotNull(received.poll(5, TimeUnit.SECONDS))

        val firstState = CoroutineScope(Dispatchers.IO).async {
            withTimeout(5_000) { conn.events.first { it is ServerStateEvent } }
        }
        kotlinx.coroutines.delay(300) // let the collector subscribe (the flow does not replay)
        serverSocket!!.send(authOk())
        serverSocket!!.send("""{"type":"server_state","channels":[{"id":3,"name":"General"}],"users":[]}""")

        val event = firstState.await() as ServerStateEvent
        assertEquals(3, event.channels.single().id)
        withTimeout(5_000) { conn.state.first { it is BridgeState.Ready } }
        conn.stop()
    }
}

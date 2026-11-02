package com.alexdyakin.lexicon.data.bridge

import android.util.Log
import com.alexdyakin.lexicon.data.ApiUrls
import com.alexdyakin.lexicon.data.di.WsOkHttp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.random.Random

/** Where the socket is in its lifecycle. */
sealed interface BridgeState {
    data object Disconnected : BridgeState
    data object Connecting : BridgeState

    /** Socket is open; the SSO handshake is in flight. */
    data object Authenticating : BridgeState
    data class Ready(val username: String, val userId: Int?, val isAdmin: Boolean) : BridgeState

    /** Terminal for this attempt; a retry follows unless [permanent]. */
    data class Failed(val reason: String, val permanent: Boolean = false) : BridgeState
}

/**
 * The single WebSocket to the Mumble bridge.
 *
 * Design points, each forced by how the bridge actually behaves:
 *
 *  - **[send] suspends until [BridgeState.Ready].** The bridge dispatches handlers
 *    without awaiting them and only flips its authenticated flag after an awaited
 *    Lexicon call, so anything written between the socket opening and auth_ok is
 *    dropped or answered with "Not authenticated". Making the sole public write path
 *    suspend removes the possibility of getting that wrong, rather than leaving it to
 *    callers to remember.
 *  - **The SSO token is minted immediately before each connect attempt**, never before
 *    a backoff wait. It is single-use and expires in sixty seconds, so minting early
 *    and then sleeping through a retry delay would spend it on nothing.
 *  - **Auth may legitimately take about twenty seconds.** The bridge chains up to
 *    three five-second Lexicon calls behind sso_auth. Timing out early would produce a
 *    reconnect loop against a server that was about to succeed.
 *  - Liveness comes from OkHttp's twenty second ping (see [WsOkHttp]); the bridge
 *    sends no heartbeat and will not notice a half-open socket.
 */
@Singleton
class BridgeConnection @Inject constructor(
    @WsOkHttp private val client: OkHttpClient,
    private val credentials: BridgeCredentials,
    private val json: Json,
) {
    /**
     * Where to connect. Overridable only so the JVM tests can point at a local
     * MockWebServer; Dagger cannot supply a defaulted constructor parameter, and a
     * bare String dependency would be ambiguous graph-wide.
     */
    internal var endpoint: String = defaultEndpoint()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<BridgeState>(BridgeState.Disconnected)
    val state: StateFlow<BridgeState> = _state.asStateFlow()

    /**
     * Every decoded inbound frame. Replay is zero: a late subscriber joins the live
     * stream, and durable state belongs in a repository layered on top of this.
     */
    private val _events = MutableSharedFlow<BridgeEvent>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<BridgeEvent> = _events.asSharedFlow()

    /** Binary audio frames, forwarded untouched. Consumed once voice ships. */
    private val _audio = MutableSharedFlow<ByteString>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val audio: SharedFlow<ByteString> = _audio.asSharedFlow()

    private var socket: WebSocket? = null
    private var lifecycle: Job? = null
    private var attempt = 0

    @Volatile
    private var issuedSessionToken: String? = null

    /**
     * The session token the bridge issued for this connection, for its HTTP routes (it is sent as
     * `Authorization: Bearer`). Null unless the connection is [BridgeState.Ready], so a token from a
     * connection that has since dropped is never used.
     */
    val sessionToken: String?
        get() = if (_state.value is BridgeState.Ready) issuedSessionToken else null

    /** Idempotent: repeated calls while already running are ignored. */
    fun start() {
        if (lifecycle?.isActive == true) return
        lifecycle = scope.launch { runForever() }
    }

    fun stop() {
        lifecycle?.cancel()
        lifecycle = null
        socket?.close(NORMAL_CLOSURE, "client stopped")
        socket = null
        _state.value = BridgeState.Disconnected
    }

    /**
     * Sends a command once the connection is ready.
     *
     * Returns false if readiness did not arrive within [timeoutMs]. There is no
     * richer success signal available: the bridge acknowledges almost nothing and
     * carries no correlation id, so a caller cannot be told whether the server acted.
     */
    suspend fun send(command: BridgeCommand, timeoutMs: Long = SEND_WAIT_MS): Boolean {
        withTimeoutOrNull(timeoutMs) { _state.first { it is BridgeState.Ready } } ?: return false
        val payload = json.encodeToString(JsonObject.serializer(), command.toJson())
        return socket?.send(payload) ?: false
    }

    /**
     * Sends a binary audio frame.
     *
     * Deliberately not suspending and not gated on readiness: this is called every
     * 20 ms from the capture loop, and a suspension point there would let frames queue
     * up behind each other and arrive late. The caller only starts once the session is
     * active, so the socket is already up; a false return simply means this frame was
     * dropped, which is the right outcome for real-time audio.
     */
    fun sendAudio(frame: ByteString): Boolean = socket?.send(frame) ?: false

    // ── internals ────────────────────────────────────────────────────────────

    private suspend fun runForever() {
        while (true) {
            if (!credentials.isSignedIn) {
                _state.value = BridgeState.Failed("Not signed in", permanent = true)
                return
            }
            val reachedReady = connectOnce()
            attempt = if (reachedReady) 0 else attempt + 1
            val wait = backoffMs(attempt.coerceAtLeast(1))
            Log.i(TAG, "reconnecting in ${wait}ms (attempt $attempt)")
            delay(wait)
        }
    }

    /** Suspends until the socket closes or fails. Returns true if it ever went Ready. */
    private suspend fun connectOnce(): Boolean {
        issuedSessionToken = null
        _state.value = BridgeState.Connecting

        // Minted here, immediately before the upgrade — see the class doc.
        val token = runCatching { credentials.mintSsoToken() }.getOrElse {
            Log.w(TAG, "SSO token mint failed", it)
            _state.value = BridgeState.Failed("Could not authenticate with Lexicon")
            return false
        }

        val closed = CompletableDeferred<Unit>()
        var reachedReady = false

        val request = Request.Builder().url(endpoint).build()
        socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    _state.value = BridgeState.Authenticating
                    // The only frame written outside send(), precisely because send()
                    // waits for the state this frame produces.
                    webSocket.send(
                        json.encodeToString(JsonObject.serializer(), SsoAuthCommand(token).toJson()),
                    )
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val event = runCatching {
                        json.decodeFromString(BridgeEventSerializer, text)
                    }.getOrElse {
                        // A malformed frame must not take the socket down.
                        Log.w(TAG, "undecodable frame: ${text.take(120)}", it)
                        return
                    }
                    when (event) {
                        is AuthOkEvent -> {
                            reachedReady = true
                            issuedSessionToken = event.sessionToken
                            _state.value =
                                BridgeState.Ready(event.username, event.userId, event.isAdmin)
                        }

                        is AuthErrorEvent ->
                            _state.value = BridgeState.Failed(
                                event.message.ifBlank { "Authentication rejected" },
                            )

                        else -> Unit
                    }
                    _events.tryEmit(event)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    _audio.tryEmit(bytes)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    // Complete the closing handshake, otherwise a server-initiated
                    // close leaves the socket half-shut and onClosed never fires.
                    webSocket.close(NORMAL_CLOSURE, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (_state.value !is BridgeState.Failed) {
                        _state.value = BridgeState.Disconnected
                    }
                    closed.complete(Unit)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "socket failed", t)
                    _state.value = BridgeState.Failed(t.message ?: "Connection lost")
                    closed.complete(Unit)
                }
            },
        )

        closed.await()
        socket = null
        return reachedReady
    }

    private fun backoffMs(attempt: Int): Long {
        val exponential = BASE_BACKOFF_MS shl (attempt - 1).coerceIn(0, 6)
        val capped = min(exponential, MAX_BACKOFF_MS)
        // Jitter, so several clients recovering from one outage do not sync up.
        return capped / 2 + Random.nextLong(capped / 2 + 1)
    }

    companion object {
        /** The https host in ApiUrls becomes a wss origin. */
        fun defaultEndpoint(): String = ApiUrls.VOICE
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")


        const val TAG = "BridgeConnection"
        const val NORMAL_CLOSURE = 1000
        const val BASE_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L

        /**
         * Generous on purpose: the bridge chains several five-second Lexicon calls
         * behind sso_auth, so readiness can legitimately be twenty seconds away.
         */
        const val SEND_WAIT_MS = 25_000L
    }
}

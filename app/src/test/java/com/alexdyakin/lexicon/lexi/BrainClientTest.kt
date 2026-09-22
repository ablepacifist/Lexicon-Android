package com.alexdyakin.lexicon.lexi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * The brain contract, against a real HTTP server. Mirrors the Pi's
 * `tests/test_obrenna_client.py`, because both clients talk to the same gateway and an
 * outage must look the same from either: a clean "unreachable", never a crash.
 */
class BrainClientTest {

    private lateinit var primary: MockWebServer
    private lateinit var fallback: MockWebServer
    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    private val brain = BrainClient(client)

    @Before
    fun setUp() {
        primary = MockWebServer().apply { start() }
        fallback = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        primary.shutdown()
        fallback.shutdown()
    }

    private fun request(vararg bases: String) = BrainRequest(
        bases = bases.toList(),
        agentToken = "a".repeat(40),
        orchestrator = "qwen3.5-4b-claude-opus-reasoning-distilled-v2",
    )

    private fun base(server: MockWebServer) = server.url("/").toString()

    private fun sse(vararg blocks: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(blocks.joinToString(""))

    private fun token(text: String, chatId: String = "chat-1") =
        "event: agent_event\ndata: {\"type\":\"token\",\"chat_id\":\"$chatId\",\"payload\":{\"text\":\"$text\"}}\n\n"

    private fun collect(request: BrainRequest, chatId: String? = null): Pair<TurnResult, List<String>> {
        val tokens = ArrayList<String>()
        val result = brain.streamTurn(request, "what time is it", "7", chatId) { tokens += it }
        return result to tokens
    }

    // ── the happy path ───────────────────────────────────────────────────────────────

    @Test
    fun `streams tokens in order and returns the final answer`() {
        primary.enqueue(
            sse(
                ": keepalive\n\n",
                "event: agent_event\ndata: {\"type\":\"phase\",\"payload\":{}}\n\n",
                token("It is "),
                token("noon."),
                "event: response\ndata: {\"chat_id\":\"chat-9\",\"message\":{\"text\":\"It is noon.\"}}\n\n",
            ),
        )

        val (result, tokens) = collect(request(base(primary)))

        assertEquals(listOf("It is ", "noon."), tokens)
        assertEquals("It is noon.", result.text)
        assertEquals("chat-9", result.chatId)
    }

    @Test
    fun `sends the same request the Pi does`() {
        primary.enqueue(sse(token("ok")))
        collect(request(base(primary)))

        val recorded = primary.takeRequest()
        assertEquals("/api/chat/stream", recorded.path)
        assertEquals("a".repeat(40), recorded.getHeader("X-Obrenna-Agent-Token"))
        assertEquals("text/event-stream", recorded.getHeader("Accept"))

        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("what time is it", body["message"]!!.jsonPrimitive.content)
        assertEquals("7", body["account_id"]!!.jsonPrimitive.content)
        assertEquals("qwen3.5-4b-claude-opus-reasoning-distilled-v2", body["orchestrator"]!!.jsonPrimitive.content)
        assertEquals("false", body["workers_enabled"]!!.jsonPrimitive.content)
        assertFalse("a new conversation sends no chat_id", body.containsKey("chat_id"))
    }

    @Test
    fun `continues an existing conversation`() {
        primary.enqueue(sse(token("ok")))
        collect(request(base(primary)), chatId = "chat-3")

        val body = Json.parseToJsonElement(primary.takeRequest().body.readUtf8()).jsonObject
        assertEquals("chat-3", body["chat_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `falls back to the joined tokens when no final response arrives`() {
        primary.enqueue(sse(token("Hello "), token("there.")))
        val (result, _) = collect(request(base(primary)))
        assertEquals("Hello there.", result.text)
        assertEquals("chat-1", result.chatId)
    }

    @Test
    fun `an error event is a brain error`() {
        primary.enqueue(sse("event: error\ndata: {\"message\":\"model not loaded\"}\n\n"))
        try {
            collect(request(base(primary)))
            fail("expected BrainError")
        } catch (e: BrainError) {
            assertTrue(e.message!!.contains("model not loaded"))
        }
    }

    // ── outages ──────────────────────────────────────────────────────────────────────

    @Test
    fun `a tunnel with no origin fails over to the next base`() {
        primary.enqueue(MockResponse().setResponseCode(530))
        fallback.enqueue(sse(token("from the LAN")))

        val (result, _) = collect(request(base(primary), base(fallback)))

        assertEquals("from the LAN", result.text)
        assertEquals(1, fallback.requestCount)
    }

    @Test
    fun `every origin-down status reads as unreachable`() {
        for (status in BrainClient.ORIGIN_DOWN_STATUSES) {
            primary.enqueue(MockResponse().setResponseCode(status))
            try {
                collect(request(base(primary)))
                fail("expected BrainUnreachable for $status")
            } catch (_: BrainUnreachable) {
            }
        }
    }

    @Test
    fun `a refused connection fails over to the next base`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        fallback.enqueue(sse(token("still here")))

        val (result, _) = collect(request("http://127.0.0.1:$closedPort", base(fallback)))

        assertEquals("still here", result.text)
    }

    @Test
    fun `nothing reachable is BrainUnreachable`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        try {
            collect(request("http://127.0.0.1:$closedPort"))
            fail("expected BrainUnreachable")
        } catch (_: BrainUnreachable) {
        }
    }

    @Test
    fun `a genuine server error is not retried elsewhere`() {
        primary.enqueue(MockResponse().setResponseCode(500))
        fallback.enqueue(sse(token("should not be used")))

        try {
            collect(request(base(primary), base(fallback)))
            fail("expected BrainError")
        } catch (_: BrainError) {
        }
        assertEquals(0, fallback.requestCount)
    }

    @Test
    fun `a rejected token is an auth error and is not retried`() {
        primary.enqueue(MockResponse().setResponseCode(401))
        fallback.enqueue(sse(token("should not be used")))

        try {
            collect(request(base(primary), base(fallback)))
            fail("expected BrainAuthError")
        } catch (_: BrainAuthError) {
        }
        assertEquals(0, fallback.requestCount)
    }

    @Test
    fun `an answer cut off after speaking has started is not repeated elsewhere`() {
        // Half the body is written before the disconnect; the padding makes sure the token
        // event lands in that half.
        primary.enqueue(
            sse(token("The first sentence. "), ": keepalive\n".repeat(400))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        fallback.enqueue(sse(token("The first sentence. ")))

        val spoken = ArrayList<String>()
        try {
            brain.streamTurn(request(base(primary), base(fallback)), "hi", "7", null) { spoken += it }
            fail("expected BrainError")
        } catch (_: BrainError) {
        }
        assertEquals(listOf("The first sentence. "), spoken)
        assertEquals(0, fallback.requestCount)
    }

    // ── cancellation ─────────────────────────────────────────────────────────────────

    @Test
    fun `cancelling the call abandons the turn without trying other bases`() {
        primary.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        fallback.enqueue(sse(token("should not be used")))

        var inFlight: Call? = null
        val canceller = Thread {
            while (inFlight == null) Thread.sleep(10)
            Thread.sleep(200)
            inFlight!!.cancel()
        }.apply { start() }

        try {
            brain.streamTurn(request(base(primary), base(fallback)), "hi", "7", null, onCall = { inFlight = it }) {}
            fail("expected TurnCancelled")
        } catch (_: TurnCancelled) {
        }
        canceller.join()
        assertEquals(0, fallback.requestCount)
    }

    // ── SSE parsing ──────────────────────────────────────────────────────────────────

    @Test
    fun `multi-line data is joined and the event name resets per block`() {
        val events = ArrayList<Pair<String, String>>()
        val source = okio.Buffer().writeUtf8("event: a\ndata: one\ndata: two\n\ndata: plain\n\n: comment\n")
        parseSse(source) { event, data -> events += event to data }
        assertEquals(listOf("a" to "one\ntwo", "message" to "plain"), events)
    }

    @Test
    fun `a final block without a trailing blank line is still delivered`() {
        var last: String? = null
        parseSse(okio.Buffer().writeUtf8("data: tail")) { _, data -> last = data }
        assertEquals("tail", last)
    }

    @Test
    fun `an empty stream delivers nothing`() {
        var seen: String? = null
        parseSse(okio.Buffer()) { _, data -> seen = data }
        assertNull(seen)
    }
}

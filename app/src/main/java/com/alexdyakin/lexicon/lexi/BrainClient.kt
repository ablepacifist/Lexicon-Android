package com.alexdyakin.lexicon.lexi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import java.io.IOException

/** No configured base answered. This is the "brain is off" signal, not a bug. */
class BrainUnreachable(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The gateway rejected the agent token (401/403). */
class BrainAuthError(message: String) : IOException(message)

/** The brain answered, and the answer was an error. */
class BrainError(message: String) : IOException(message)

/** The turn was cancelled locally, for example because the user tapped Cancel. */
class TurnCancelled : IOException("Turn cancelled")

data class BrainRequest(
    /** Tried in order: the tunnel first, then any LAN address. */
    val bases: List<String>,
    val agentToken: String,
    val orchestrator: String,
    val workersEnabled: Boolean = false,
)

data class TurnResult(val chatId: String?, val text: String)

/**
 * Sends a transcript to Obrenna's existing `POST /api/chat/stream` and streams the answer
 * back token by token.
 *
 * A port of `lexi/obrenna_client.py`: the same body, the same `X-Obrenna-Agent-Token`
 * header, the same SSE event shapes, and the same rule for when a failure means "try the
 * next base" versus "that was a real answer". Only the transcript crosses the network.
 */
class BrainClient(
    private val http: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    /**
     * Runs one turn, calling [onToken] for every answer token as it arrives.
     *
     * Blocking, so call it off the main thread. [onCall] receives each HTTP call as it
     * starts; cancelling that call is how a turn in progress is abandoned, since a blocked
     * socket read ignores coroutine cancellation.
     *
     * @throws BrainUnreachable when no base answered
     * @throws BrainAuthError when the token was refused
     * @throws BrainError when the brain answered with an error
     * @throws TurnCancelled when the call was cancelled through [onCall]
     */
    fun streamTurn(
        request: BrainRequest,
        message: String,
        accountId: String,
        chatId: String?,
        onCall: (Call) -> Unit = {},
        onToken: (String) -> Unit,
    ): TurnResult {
        val body = buildJsonObject {
            put("message", message)
            put("account_id", accountId)
            put("orchestrator", request.orchestrator)
            put("workers_enabled", request.workersEnabled)
            if (!chatId.isNullOrEmpty()) put("chat_id", chatId)
        }.toString()

        val bases = request.bases.map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }
        var lastError: Throwable? = null
        for (base in bases) {
            val call = http.newCall(
                Request.Builder()
                    .url("$base/api/chat/stream")
                    .header("Accept", "text/event-stream")
                    .apply { if (request.agentToken.isNotEmpty()) header(TOKEN_HEADER, request.agentToken) }
                    .post(body.toRequestBody(JSON))
                    .build(),
            )
            onCall(call)
            val tokens = TokenCounter(onToken)
            try {
                return streamOne(call, chatId, tokens)
            } catch (e: OriginDown) {
                lastError = e
            } catch (e: IOException) {
                if (call.isCanceled()) throw TurnCancelled()
                if (e is BrainAuthError || e is BrainError) throw e
                // A connection that never produced an answer can be retried elsewhere.
                // Once words have been spoken we are committed: retrying would say them twice.
                if (tokens.count > 0) throw BrainError("The answer was cut off: ${e.message}")
                lastError = e
            }
        }
        throw BrainUnreachable("No Obrenna base reachable (${bases.joinToString()})", lastError)
    }

    private fun streamOne(call: Call, initialChatId: String?, onToken: TokenCounter): TurnResult {
        call.execute().use { response ->
            when {
                response.code == 401 || response.code == 403 ->
                    throw BrainAuthError("Gateway rejected the agent token (${response.code})")
                // A gateway that is up with nothing behind it is still an outage. Cloudflare
                // answers 530 when the tunnel has no connected origin, which is exactly what a
                // powered-off alison looks like from a phone.
                response.code in ORIGIN_DOWN_STATUSES -> throw OriginDown(response.code)
                !response.isSuccessful -> throw BrainError("Brain returned ${response.code}")
            }

            var chatId = initialChatId
            val parts = StringBuilder()
            var finalText: String? = null

            parseSse(response.body!!.source()) { event, data ->
                when (event) {
                    "agent_event" -> {
                        val envelope = parseObject(data) ?: return@parseSse
                        envelope.string("chat_id")?.let { chatId = it }
                        if (envelope.string("type") == "token") {
                            val token = (envelope["payload"] as? JsonObject)?.string("text").orEmpty()
                            if (token.isNotEmpty()) {
                                parts.append(token)
                                onToken(token)
                            }
                        }
                    }

                    "response" -> {
                        val envelope = parseObject(data) ?: JsonObject(emptyMap())
                        envelope.string("chat_id")?.let { chatId = it }
                        finalText = (envelope["message"] as? JsonObject)?.string("text")
                    }

                    "error" -> {
                        val envelope = parseObject(data) ?: JsonObject(emptyMap())
                        throw BrainError("Brain error: ${envelope.string("message") ?: "unknown"}")
                    }
                }
            }
            return TurnResult(chatId, finalText ?: parts.toString())
        }
    }

    private fun parseObject(data: String): JsonObject? =
        runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull()

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    /** Counts tokens so a failure can tell whether anything was already spoken. */
    private class TokenCounter(private val sink: (String) -> Unit) : (String) -> Unit {
        var count = 0
            private set

        override fun invoke(token: String) {
            count++
            sink(token)
        }
    }

    private class OriginDown(code: Int) : IOException("Origin down ($code)")

    companion object {
        private const val TOKEN_HEADER = "X-Obrenna-Agent-Token"
        private val JSON = "application/json".toMediaType()

        /** Statuses meaning "the origin behind me is not there", as opposed to "no". */
        val ORIGIN_DOWN_STATUSES = setOf(502, 503, 504, 520, 521, 522, 523, 524, 530)
    }
}

/**
 * Minimal Server-Sent Events parser, the same rules as `_iter_sse` in the Pi's client.
 *
 * Emits (event, data) once per blank-line-terminated block, defaults the event name to
 * "message", and ignores comment lines such as the brain's `: keepalive`.
 */
internal fun parseSse(source: BufferedSource, onEvent: (event: String, data: String) -> Unit) {
    var event = "message"
    val data = ArrayList<String>()
    while (true) {
        val line = source.readUtf8Line() ?: break
        when {
            line.isEmpty() -> {
                if (data.isNotEmpty()) onEvent(event, data.joinToString("\n"))
                event = "message"
                data.clear()
            }
            line.startsWith(":") -> Unit
            line.startsWith("event:") -> event = line.removePrefix("event:").trim()
            line.startsWith("data:") -> data += line.removePrefix("data:").trimStart()
        }
    }
    if (data.isNotEmpty()) onEvent(event, data.joinToString("\n"))
}

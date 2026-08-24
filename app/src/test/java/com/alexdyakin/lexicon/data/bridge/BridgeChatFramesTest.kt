package com.alexdyakin.lexicon.data.bridge

import com.alexdyakin.lexicon.ui.voice.shortTime
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Chat-specific decoding: the three text origins, history rows and id leniency. */
class BridgeChatFramesTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private fun decode(text: String): BridgeEvent =
        json.decodeFromString(BridgeEventSerializer, text)

    /**
     * A web message has no `source` key at all — the absence is the signal. Treating a
     * missing source as unknown rather than web would mislabel every app message.
     */
    @Test
    fun `a web message is identified by the absence of a source`() {
        val event = decode(
            """
            {"type":"text","channelId":1,"userId":7,"username":"alex","text":"hi",
             "html":"hi","timestamp":"2026-08-16T14:43:48.835Z","id":"1234","replyToId":null}
            """.trimIndent(),
        ) as TextEvent
        assertEquals(MessageOrigin.WEB, event.origin)
        assertEquals(7, event.userId)
        assertEquals("1234", event.id)
        assertNull(event.replyToId)
    }

    @Test
    fun `mumble and discord relays are tagged by source`() {
        val mumble = decode(
            """{"type":"text","channelId":1,"username":"bob","text":"hey","source":"mumble","timestamp":"t"}""",
        ) as TextEvent
        assertEquals(MessageOrigin.MUMBLE, mumble.origin)
        // Relays carry no ids at all.
        assertNull(mumble.id)
        assertNull(mumble.userId)

        val discord = decode(
            """{"type":"text","channelId":1,"username":"carol","text":"yo","source":"discord","timestamp":"t"}""",
        ) as TextEvent
        assertEquals(MessageOrigin.DISCORD, discord.origin)
    }

    /**
     * The bridge stringifies ids on some paths and echoes raw numbers on others.
     * Both must land on the same String so a cache can key on one type.
     */
    @Test
    fun `message ids decode from either a number or a string`() {
        val numeric = decode("""{"type":"text","channelId":1,"username":"a","text":"x","id":9876}""") as TextEvent
        assertEquals("9876", numeric.id)

        val stringy = decode("""{"type":"text","channelId":1,"username":"a","text":"x","id":"9876"}""") as TextEvent
        assertEquals("9876", stringy.id)
    }

    @Test
    fun `history decodes lexicon message rows`() {
        val event = decode(
            """
            {"type":"history","channelId":3,"_isRefresh":false,
             "messages":[{"id":42,"channelId":3,"channelName":"general","userId":7,
                          "username":"alex","content":"hello","messageType":"TEXT",
                          "isPinned":true,"createdAt":"2026-08-16T14:43:48.835365"}]}
            """.trimIndent(),
        ) as HistoryEvent
        assertEquals(3, event.channelId)
        val row = event.messages.single()
        assertEquals("42", row.id)
        assertEquals("hello", row.content)
        assertTrue(row.isPinned)
        // Kept verbatim: it is also the pagination cursor.
        assertEquals("2026-08-16T14:43:48.835365", row.createdAt)
    }

    /**
     * Omitting channelId on the request makes the bridge answer with no channelId at
     * all, which is exactly why the command always writes one.
     */
    @Test
    fun `history tolerates a missing channelId`() {
        val event = decode("""{"type":"history","messages":[]}""") as HistoryEvent
        assertNull(event.channelId)
    }

    @Test
    fun `history and text commands carry their fields`() {
        val history = GetHistoryCommand(channelId = 4, limit = 25, before = "2026-01-01T00:00:00").toJson()
        assertEquals("get_history", history["type"]!!.jsonPrimitiveContent())
        assertEquals("4", history["channelId"].toString())
        assertEquals("25", history["limit"].toString())
        assertEquals("2026-01-01T00:00:00", history["before"]!!.jsonPrimitiveContent())

        // `before` is omitted entirely on a first page rather than sent as null.
        val firstPage = GetHistoryCommand(channelId = 4).toJson()
        assertTrue(firstPage["before"] == null)

        val text = SendTextCommand(channelId = 4, text = "hi", replyToMessageId = "12").toJson()
        assertEquals("text", text["type"]!!.jsonPrimitiveContent())
        assertEquals("hi", text["text"]!!.jsonPrimitiveContent())
        assertEquals("12", text["replyToMessageId"]!!.jsonPrimitiveContent())
    }

    @Test
    fun `clock time is pulled from both timestamp formats`() {
        // Lexicon's LocalDateTime: no zone, microseconds.
        assertEquals("14:43", shortTime("2026-08-16T14:43:48.835365"))
        // The bridge's own toISOString: milliseconds and a Z.
        assertEquals("14:43", shortTime("2026-08-16T14:43:48.835Z"))
        // Junk must not crash a row.
        assertEquals("", shortTime(""))
        assertEquals("", shortTime("not-a-date"))
    }
}

private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveContent(): String =
    kotlinx.serialization.json.Json.parseToJsonElement(toString()).let {
        (it as kotlinx.serialization.json.JsonPrimitive).content
    }

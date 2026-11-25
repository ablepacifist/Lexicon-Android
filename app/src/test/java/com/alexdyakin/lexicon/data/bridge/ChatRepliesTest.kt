package com.alexdyakin.lexicon.data.bridge

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Replies (the bridge's `replyTo` preview) and not showing a relayed message twice. */
class ChatRepliesTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private fun decode(text: String): BridgeEvent = json.decodeFromString(BridgeEventSerializer, text)

    @Test
    fun `a live reply carries the bridge's preview of the original`() {
        val event = decode(
            """{"type":"text","channelId":1,"username":"bob","text":"yes","id":51,"replyToId":"40",
               "replyTo":{"id":"40","username":"alex","text":"dinner at 8?"}}""",
        ) as TextEvent
        assertEquals("40", event.replyToId)
        assertEquals(ReplyPreview(id = "40", username = "alex", text = "dinner at 8?"), event.replyTo)
    }

    @Test
    fun `a discord reply to an unknown original has a quote but no id`() {
        val event = decode(
            """{"type":"text","channelId":1,"username":"Erin","text":"agreed","source":"discord","id":60,
               "replyToId":null,"replyTo":{"id":null,"username":"old","text":"from before"}}""",
        ) as TextEvent
        assertNull(event.replyToId)
        assertNull(event.replyTo!!.id)
        assertEquals("old", event.replyTo!!.username)
    }

    @Test
    fun `history rows bring their reply previews`() {
        val event = decode(
            """{"type":"history","channelId":1,"messages":[
               {"id":50,"channelId":1,"username":"bob","content":"ok","replyToId":40,
                "replyTo":{"id":"40","username":"alex","text":"see you"},"createdAt":"2026-11-24T10:00:00"}]}""",
        ) as HistoryEvent
        val row = event.messages.single()
        assertEquals("40", row.replyToId)
        assertEquals("see you", row.replyTo!!.text)
    }

    @Test
    fun `an older bridge without previews still decodes`() {
        val event = decode("""{"type":"text","channelId":1,"username":"a","text":"x","id":1,"replyToId":7}""") as TextEvent
        assertEquals("7", event.replyToId)
        assertNull(event.replyTo)
    }

    @Test
    fun `only messages with a real lexicon id can be replied to`() {
        assertTrue(canReplyTo(line("1234")))
        assertFalse(canReplyTo(line("relay-2026-bob-99")))
        assertFalse(canReplyTo(line("img-5")))
        assertFalse(canReplyTo(line("")))
    }

    @Test
    fun `a live relay with no id is replaced when history brings it back with one`() {
        val live = listOf(
            line("relay-t-dave-1", "dave", "hello"),
            line("88", "alex", "hi"),
            line("relay-t-dave-2", "dave", "hello"),
        )
        val fromHistory = listOf(line("120", "dave", "hello"))
        val kept = withoutIdlessCopies(live, fromHistory)
        assertEquals(listOf("88", "relay-t-dave-2"), kept.map { it.id })
    }

    private fun line(id: String, username: String = "u", text: String = "t") =
        ChatLine(id = id, username = username, text = text, timestamp = "t", origin = MessageOrigin.WEB)
}

package com.alexdyakin.lexicon.data.bridge

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The direct-message frames the DM surface now depends on.
 *
 * These are decode tests rather than behaviour tests because the interesting decisions
 * here are all about the server's shapes: which key names it uses, which fields it
 * omits, and what an absent value is allowed to mean.
 */
class DmFramesTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun decode(raw: String) = json.decodeFromString(BridgeEventSerializer, raw)

    @Test
    fun `dm_opened carries the conversation id, which is the only way to learn it`() {
        val event = decode(
            """{"type":"dm_opened","conversationId":7,"otherUsername":"alex","otherUserId":3}""",
        )
        assertTrue(event is DmOpenedEvent)
        event as DmOpenedEvent
        assertEquals(7L, event.conversationId)
        assertEquals("alex", event.otherUsername)
        assertEquals(3, event.otherUserId)
    }

    @Test
    fun `dm_history rows tolerate a numeric id`() {
        val event = decode(
            """
            {"type":"dm_history","conversationId":7,"messages":[
              {"id":41,"fromUsername":"alex","fromUserId":3,"content":"hi","timestamp":"2026-08-16T14:43:48.835365"},
              {"id":"42","fromUsername":"zx","fromUserId":9,"content":"hello","timestamp":"2026-08-16T14:44:00.100000"}
            ]}
            """.trimIndent(),
        )
        assertTrue(event is DmHistoryEvent)
        event as DmHistoryEvent
        // Both forms land on the string key the caches use.
        assertEquals(listOf("41", "42"), event.messages.map { it.id })
        assertEquals("hi", event.messages[0].content)
    }

    /**
     * A failed lookup and a conversation nobody has written in produce byte-identical
     * frames. This pins that fact so nobody later builds an error path on top of it.
     */
    @Test
    fun `an empty dm_history is indistinguishable from a failure`() {
        val failure = decode("""{"type":"dm_history","conversationId":7,"messages":[]}""")
        val empty = decode("""{"type":"dm_history","conversationId":7,"messages":[]}""")
        assertEquals(failure, empty)
        assertTrue((failure as DmHistoryEvent).messages.isEmpty())
    }

    @Test
    fun `dm_unread_counts decodes into the map the badge reads`() {
        val event = decode(
            """{"type":"dm_unread_counts","unreads":[
                 {"conversationId":7,"unreadCount":3},
                 {"conversationId":8,"unreadCount":0}
               ]}""",
        )
        assertTrue(event is DmUnreadCountsEvent)
        val counts = (event as DmUnreadCountsEvent).unreads.associate { it.conversationId to it.unreadCount }
        assertEquals(mapOf(7L to 3, 8L to 0), counts)
    }

    @Test
    fun `dm_unread_update replaces a count rather than adding to it`() {
        val event = decode(
            """{"type":"dm_unread_update","conversationId":7,"fromUsername":"alex","unreadCount":1}""",
        )
        event as DmUnreadUpdateEvent
        val before = mapOf(7L to 5)
        // The server sends the new total, so the reducer overwrites.
        assertEquals(mapOf(7L to 1), before + (event.conversationId to event.unreadCount))
    }

    @Test
    fun `a conversation with no messages yet has a null lastMessageAt`() {
        val event = decode(
            """{"type":"dm_conversations_list","conversations":[
                 {"id":7,"otherUsername":"alex","otherUserId":3,"createdAt":"2026-08-16T14:00:00.000000"}
               ]}""",
        )
        val conversation = (event as DmConversationsEvent).conversations.single()
        assertEquals(null, conversation.lastMessageAt)
        assertEquals("2026-08-16T14:00:00.000000", conversation.createdAt)
    }

    /** An unmodelled DM-adjacent type must not take the socket down. */
    @Test
    fun `an unknown dm type falls through to UnknownEvent`() {
        val event = decode("""{"type":"dm_typing","conversationId":7}""")
        assertTrue(event is UnknownEvent)
        assertEquals("dm_typing", event.type)
    }
}

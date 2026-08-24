package com.alexdyakin.lexicon.data.bridge

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Presence, typing, reactions, DMs and mentions — the traps the bridge sets. */
class SocialFramesTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private fun decode(t: String): BridgeEvent = json.decodeFromString(BridgeEventSerializer, t)

    /**
     * The column is a MySQL TINYINT(1), so mysql2 sends 0/1 as a JSON number. Modelling
     * it as a Boolean would fail to parse the whole notification list.
     */
    @Test
    fun `isRead arrives as an int, not a boolean`() {
        val event = decode(
            """
            {"type":"notifications","unreadCount":2,
             "notifications":[{"id":5,"notifType":"mention","fromUsername":"alex",
                               "channelId":1,"channelName":"general","messageId":9,
                               "messagePreview":"hi","isRead":0,
                               "createdAt":"2026-08-16T14:43:48.835Z"}]}
            """.trimIndent(),
        ) as NotificationsEvent
        val row = event.notifications.single()
        assertEquals(0, row.isRead)
        assertFalse(row.read)
        assertEquals(2, event.unreadCount)
    }

    /**
     * The live push is nested under `notification` and lacks messageId/isRead, so it
     * cannot share a model with the list rows.
     */
    @Test
    fun `the mention push has its own shape`() {
        val event = decode(
            """
            {"type":"notification","notification":{"id":7,"notifType":"mention",
             "fromUsername":"bob","channelId":1,"channelName":"general",
             "messagePreview":"hey @alex","createdAt":"2026-08-16T14:43:48.835Z"}}
            """.trimIndent(),
        ) as NotificationPushEvent
        assertEquals("bob", event.notification.fromUsername)
        assertEquals("hey @alex", event.notification.messagePreview)
    }

    @Test
    fun `reaction_update carries no channelId on the get_reactions reply`() {
        val broadcast = decode(
            """{"type":"reaction_update","messageId":42,"channelId":1,
                "reactions":[{"emoji":"👍","count":2,"users":["alex","bob"]}]}""",
        ) as ReactionUpdateEvent
        assertEquals("42", broadcast.messageId)
        assertEquals(1, broadcast.channelId)
        assertEquals(2, broadcast.reactions.single().count)

        val reply = decode("""{"type":"reaction_update","messageId":"42","reactions":[]}""") as ReactionUpdateEvent
        assertNull("the direct reply omits channelId", reply.channelId)
    }

    @Test
    fun `web presence and mumble presence stay separate`() {
        val web = decode(
            """{"type":"web_users","webClients":[{"id":"web_1","username":"zx","channelId":0,
                "inVoice":false,"voiceChannelId":null,"avatarUrl":"/a.png"}]}""",
        ) as WebUsersEvent
        assertEquals("web_1", web.webClients.single().id)

        val mumble = decode(
            """{"type":"user_update","user":{"session":42,"name":"web_zx","channelId":1}}""",
        ) as UserUpdateEvent
        // Same person, different identity space: session id vs web_<userId>.
        assertEquals(42L, mumble.user.session)
        assertEquals("web_zx", mumble.user.name)
    }

    @Test
    fun `a dm can arrive with a null id`() {
        val event = decode(
            """{"type":"dm_message","conversationId":3,"id":null,"fromUserId":1,
                "fromUsername":"zx","toUsername":"alex","content":"hi","timestamp":"t"}""",
        ) as DmMessageEvent
        assertNull("the Lexicon store can fail while the DM still delivers", event.id)
        assertEquals(3L, event.conversationId)
    }

    @Test
    fun `dm commands use the inconsistent keys the server expects`() {
        // dm_open takes `username`; dm_send takes `toUsername`. Swapping them silently
        // fails, because the server only checks for its own key.
        assertTrue(DmOpenCommand("alex").toJson().containsKey("username"))
        assertFalse(DmOpenCommand("alex").toJson().containsKey("toUsername"))
        assertTrue(DmSendCommand("alex", "hi").toJson().containsKey("toUsername"))
    }

    @Test
    fun `reaction batch is capped at the server limit`() {
        val ids = (1..250).map { it.toString() }
        val payload = GetReactionsBatchCommand(ids).toJson()
        val sent = payload["messageIds"]!!.toString().split(",").size
        assertEquals("the server ignores anything past 100", 100, sent)
    }

    @Test
    fun `typing decodes both directions`() {
        val on = decode("""{"type":"typing","username":"bob","userId":2,"channelId":1,"typing":true}""") as TypingEvent
        assertTrue(on.typing)
        val off = decode("""{"type":"typing","username":"bob","userId":2,"channelId":1,"typing":false}""") as TypingEvent
        assertFalse(off.typing)
    }

    // ── optimistic reaction maths ────────────────────────────────────────────

    @Test
    fun `adding a new emoji creates a pill`() {
        val out = applyLocalReaction(emptyList(), "👍", "zx", add = true)
        assertEquals(1, out.single().count)
        assertEquals(listOf("zx"), out.single().users)
    }

    @Test
    fun `adding to an existing emoji increments once`() {
        val start = listOf(Reaction("👍", 1, listOf("alex")))
        val out = applyLocalReaction(start, "👍", "zx", add = true)
        assertEquals(2, out.single().count)
        // Adding twice must not double-count the same person.
        val again = applyLocalReaction(out, "👍", "zx", add = true)
        assertEquals(2, again.single().count)
    }

    @Test
    fun `removing the last reactor drops the pill entirely`() {
        val start = listOf(Reaction("👍", 1, listOf("zx")))
        assertTrue(applyLocalReaction(start, "👍", "zx", add = false).isEmpty())
    }

    @Test
    fun `removing one of several just decrements`() {
        val start = listOf(Reaction("👍", 2, listOf("alex", "zx")))
        val out = applyLocalReaction(start, "👍", "zx", add = false)
        assertEquals(1, out.single().count)
        assertEquals(listOf("alex"), out.single().users)
    }
}

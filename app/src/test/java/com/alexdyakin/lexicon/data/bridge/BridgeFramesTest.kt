package com.alexdyakin.lexicon.data.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bridge has no protocol spec and no versioning. These tests pin the decoding
 * rules that the transport depends on, so a change in the bridge shows up here rather
 * than as a silently dead socket.
 */
class BridgeFramesTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private fun decode(text: String): BridgeEvent =
        json.decodeFromString(BridgeEventSerializer, text)

    @Test
    fun `server_state decodes channels and mumble users`() {
        val event = decode(
            """
            {"type":"server_state",
             "channels":[{"id":1,"name":"general","parentId":0,"description":"d"}],
             "users":[{"session":42,"name":"bob","channelId":1,"mute":false,"deaf":false,
                       "selfMute":false,"selfDeaf":false}]}
            """.trimIndent(),
        )
        assertTrue(event is ServerStateEvent)
        val state = event as ServerStateEvent
        assertEquals(1, state.channels.size)
        assertEquals("general", state.channels.first().name)
        assertEquals(42L, state.users.first().session)
        assertEquals("bob", state.users.first().name)
    }

    /**
     * The bridge deletes the `restricted` key when unrestricting rather than setting
     * it false, so an absent key must read as unrestricted.
     */
    @Test
    fun `absent restricted flag means unrestricted`() {
        val open = decode("""{"type":"channel_update","channel":{"id":2,"name":"open"}}""")
        assertFalse((open as ChannelUpdateEvent).channel.restricted)

        val locked = decode(
            """{"type":"channel_update","channel":{"id":3,"name":"secret","restricted":true}}""",
        )
        assertTrue((locked as ChannelUpdateEvent).channel.restricted)
    }

    @Test
    fun `auth_ok carries identity and the sso marker`() {
        val event = decode(
            """{"type":"auth_ok","username":"alex","userId":7,"isAdmin":false,"sso":true}""",
        ) as AuthOkEvent
        assertEquals("alex", event.username)
        assertEquals(7, event.userId)
        assertTrue(event.sso)
    }

    /**
     * The bridge can hand back `userId: null` when Lexicon is unreachable. That user
     * can never see restricted channels, so the null must survive decoding rather than
     * being coerced to 0 and mistaken for a real account.
     */
    @Test
    fun `a null userId survives decoding`() {
        val event = decode("""{"type":"auth_ok","username":"alex","userId":null}""") as AuthOkEvent
        assertEquals(null, event.userId)
    }

    /**
     * The single most important decoding rule: roughly forty message types exist and
     * the set grows. An unmodelled type must be inert, never fatal.
     */
    @Test
    fun `an unmodelled type decodes to UnknownEvent instead of throwing`() {
        val event = decode("""{"type":"some_future_feature","payload":{"a":1}}""")
        assertTrue(event is UnknownEvent)
        assertEquals("some_future_feature", event.type)
        assertTrue((event as UnknownEvent).raw.containsKey("payload"))
    }

    @Test
    fun `a frame with no type at all is still inert`() {
        val event = decode("""{"nonsense":true}""")
        assertTrue(event is UnknownEvent)
        assertEquals("unknown", event.type)
    }

    @Test
    fun `user_remove tolerates the missing name the bridge omits`() {
        val known = decode("""{"type":"user_remove","session":9,"name":"bob"}""") as UserRemoveEvent
        assertEquals("bob", known.name)
        // The bridge omits `name` entirely when the session was not in its map.
        val unknown = decode("""{"type":"user_remove","session":9}""") as UserRemoveEvent
        assertEquals(null, unknown.name)
        assertEquals(9L, unknown.session)
    }

    /**
     * A computed `type` property has no backing field and would never reach the wire,
     * and a defaulted constructor property would depend on the DI Json keeping
     * `encodeDefaults` on. Commands build their own object for exactly that reason.
     */
    @Test
    fun `commands put their type on the wire`() {
        val sso = SsoAuthCommand("abc123").toJson()
        assertEquals("sso_auth", sso["type"]!!.toString().trim('"'))
        assertEquals("abc123", sso["token"]!!.toString().trim('"'))

        val join = JoinChannelCommand(5).toJson()
        assertEquals("join_channel", join["type"]!!.toString().trim('"'))
        assertEquals("5", join["channelId"].toString())
    }

    @Test
    fun `command json survives a strict encoder`() {
        // encodeDefaults deliberately off: the type must be present regardless.
        val strict = Json { encodeDefaults = false }
        val text = strict.encodeToString(JsonObject.serializer(), JoinChannelCommand(3).toJson())
        assertTrue("type missing from $text", text.contains("\"type\":\"join_channel\""))
        assertTrue(text.contains("\"channelId\":3"))
    }
}

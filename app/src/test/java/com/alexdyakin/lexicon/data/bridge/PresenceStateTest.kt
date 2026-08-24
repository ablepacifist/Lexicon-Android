package com.alexdyakin.lexicon.data.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Our own entry in the member roster.
 *
 * The bridge deletes a presence entry from the socket's close handler without checking
 * that the entry still belongs to that socket. Reconnect quickly enough and the old
 * handler runs *after* the new socket has authenticated, so it removes the live entry
 * and announces that we left — while we are sitting on an open, authenticated socket.
 * This was observed against the live bridge: two reconnects in thirty seconds left the
 * member list showing nobody online, including the person reading it.
 *
 * Fixing it properly needs a bridge change. Client-side, our own connection is proof
 * enough, so these pin that we never remove or omit ourselves.
 */
class PresenceStateTest {

    private val ready = BridgeState.Ready(username = "zx", userId = 9, isAdmin = false)
    private val me = "web_9"

    private fun connected(clients: List<WebClient> = emptyList()) =
        BridgeChatState(connection = ready, webClients = clients)

    @Test
    fun `our presence key is derived from the authenticated user id`() {
        assertEquals(me, connected().myWebClientId)
    }

    @Test
    fun `before authentication we have no presence key`() {
        assertNull(BridgeChatState().myWebClientId)
    }

    @Test
    fun `a roster that forgot us gets us back`() {
        val others = listOf(WebClient(id = "web_3", username = "alex"))
        val healed = connected().withSelf(others)

        assertEquals(listOf("web_3", me), healed.map { it.id })
        assertEquals("zx", healed.last().username)
    }

    @Test
    fun `a roster that already lists us is left alone`() {
        val roster = listOf(
            WebClient(id = "web_3", username = "alex"),
            WebClient(id = me, username = "zx", inVoice = true),
        )
        val healed = connected().withSelf(roster)

        assertEquals(roster, healed)
        // Specifically, the server's richer copy is not replaced by our stub.
        assertTrue(healed.single { it.id == me }.inVoice)
    }

    @Test
    fun `an unauthenticated state cannot invent a self entry`() {
        val roster = listOf(WebClient(id = "web_3", username = "alex"))
        assertEquals(roster, BridgeChatState().withSelf(roster))
    }
}

package com.alexdyakin.lexicon.data.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The game servers panel: the bridge's `games_*` frames and the display rules.
 *
 * The status fixture is the one `mumble-bridge/tests/test-game-servers.js` uses, so a
 * change to the manager's shape fails here as well as there. The display tests restate
 * `card()` in `public/js/games.js`; the two clients must draw the same buttons.
 */
class GamesFramesTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
    private fun decode(t: String): BridgeEvent = json.decodeFromString(BridgeEventSerializer, t)

    /** The admin view: carries main_pid and restarts, which a normal player never gets. */
    private val adminStatus = """
        {"type":"games_status","available":true,
         "caps":{"view":true,"start":true,"requestRestart":true,"admin":true},
         "servers":[
           {"id":"mc-java","name":"Minecraft Java","type":"minecraft-java","state":"active","since":1,
            "players":["steve"],"player_count":1,"max_players":10,"main_pid":1234,"restarts":3,
            "can_start":false,"public":{"start":true,"restart_request":true},
            "join_address":"play.example:25565"},
           {"id":"palworld","name":"Palworld","type":"palworld","state":"inactive","players":null,
            "player_count":null,"main_pid":0,"restarts":0,"can_start":true,
            "public":{"start":true,"restart_request":true}}
         ],
         "restart_requests":[
           {"id":"ab12","server":"mc-java","created":100,"reason":"lag",
            "supporters":[{"name":"bob"},{"name":"alex"}],"actors":[{"id":7}]}
         ],
         "mem_available_gb":19}
    """.trimIndent()

    // ── decoding ─────────────────────────────────────────────────────────────

    @Test
    fun `games_status decodes the manager's snake_case shape`() {
        val event = decode(adminStatus) as GamesStatusEvent
        assertTrue(event.available)
        assertTrue(event.caps.admin)
        assertEquals(19.0, event.memAvailableGb!!, 0.0)

        val mc = event.servers.first { it.id == "mc-java" }
        assertTrue(mc.online)
        assertEquals(listOf("steve"), mc.players)
        assertEquals(1, mc.playerCount)
        assertEquals(10, mc.maxPlayers)
        assertEquals("play.example:25565", mc.joinAddress)
        assertEquals(3, mc.restarts)
        assertTrue(mc.public!!.restartRequest)

        val request = event.restartRequests.single()
        assertEquals("mc-java", request.server)
        assertEquals(listOf("bob", "alex"), request.supporters.map { it.name })
    }

    /**
     * An offline server sends `players: null`, not `[]`. That null is meaningful — it is
     * what tells "unknown" from "nobody" — so it must survive decoding as null.
     */
    @Test
    fun `null players stay null rather than becoming an empty list`() {
        val palworld = (decode(adminStatus) as GamesStatusEvent).servers.first { it.id == "palworld" }
        assertFalse(palworld.online)
        assertNull(palworld.players)
        assertNull(palworld.playerCount)
    }

    /** The bridge with no manager configured answers like this; it must not look like "no servers". */
    @Test
    fun `an unavailable status keeps its error`() {
        val event = decode(
            """{"type":"games_status","caps":{"view":true},"available":false,
                "error":"Game servers are not set up on this bridge","servers":[],"restart_requests":[]}""",
        ) as GamesStatusEvent
        assertFalse(event.available)
        assertEquals("Game servers are not set up on this bridge", event.error)
    }

    @Test
    fun `action results carry message on success and error on refusal`() {
        val ok = decode(
            """{"type":"games_action_result","action":"games_start","serverId":"palworld","ok":true,"message":"Starting"}""",
        ) as GamesActionResultEvent
        assertEquals("Starting", ok.text)

        val refused = decode(
            """{"type":"games_action_result","action":"games_stop","serverId":"mc-java","ok":false,"error":"Only an admin can do that"}""",
        ) as GamesActionResultEvent
        assertFalse(refused.ok)
        assertEquals("Only an admin can do that", refused.text)
    }

    @Test
    fun `logs, command results and notices decode`() {
        val logs = decode("""{"type":"games_logs","serverId":"mc-java","lines":["a","b"]}""") as GamesLogsEvent
        assertEquals(listOf("a", "b"), logs.lines)

        val result = decode(
            """{"type":"games_command_result","serverId":"mc-java","ok":false,"error":"not running"}""",
        ) as GamesCommandResultEvent
        assertEquals("not running", result.error)

        val notice = decode("""{"type":"games_notice","text":"bob started Palworld"}""") as GamesNoticeEvent
        assertEquals("bob started Palworld", notice.text)
    }

    // ── commands ─────────────────────────────────────────────────────────────

    @Test
    fun `commands use the bridge's message types and field names`() {
        assertEquals("games_list", GamesListCommand().toJson()["type"]!!.jsonPrimitive.content)

        val start = GamesServerCommand(GamesServerCommand.Action.START, "palworld").toJson()
        assertEquals("games_start", start["type"]!!.jsonPrimitive.content)
        assertEquals("palworld", start["serverId"]!!.jsonPrimitive.content)
        assertEquals("games_stop", GamesServerCommand(GamesServerCommand.Action.STOP, "x").type)
        assertEquals("games_restart", GamesServerCommand(GamesServerCommand.Action.RESTART, "x").type)

        assertEquals("games_approve_request", GamesDecideRequestCommand("ab12", approve = true).type)
        val deny = GamesDecideRequestCommand("ab12", approve = false).toJson()
        assertEquals("games_deny_request", deny["type"]!!.jsonPrimitive.content)
        assertEquals("ab12", deny["requestId"]!!.jsonPrimitive.content)

        assertEquals(200, GamesLogsCommand("mc-java").toJson()["lines"]!!.jsonPrimitive.int)
    }

    /** The bridge cuts reasons at 200 and commands at 500; sending more is wasted. */
    @Test
    fun `reasons and console commands are trimmed to the bridge's limits`() {
        val reason = GamesRequestRestartCommand("mc-java", "  " + "r".repeat(300) + "  ").toJson()
        assertEquals("games_request_restart", reason["type"]!!.jsonPrimitive.content)
        assertEquals(200, reason["reason"]!!.jsonPrimitive.content.length)

        val command = GamesConsoleCommand("mc-java", " say hi ").toJson()
        assertEquals("games_command", command["type"]!!.jsonPrimitive.content)
        assertEquals("say hi", command["command"]!!.jsonPrimitive.content)
        assertEquals(500, GamesConsoleCommand("x", "c".repeat(900)).toJson()["command"]!!.jsonPrimitive.content.length)
    }

    // ── display rules (games.js card()) ──────────────────────────────────────

    private val user = GamesCaps(view = true, start = true, requestRestart = true, admin = false)
    private val admin = GamesCaps(view = true, start = true, requestRestart = true, admin = true)
    private val viewer = GamesCaps(view = true)
    private val open = GamePublicRights(start = true, restartRequest = true)

    private fun server(state: String, canStart: Boolean = true, public: GamePublicRights? = open) =
        GameServer(id = "s", name = "S", state = state, canStart = canStart, public = public)

    @Test
    fun `a normal player can start an offline server and ask to restart an online one`() {
        val offline = GamesDisplay.buttons(server("inactive"), user)
        assertTrue(offline.showStart)
        assertTrue(offline.startEnabled)
        assertFalse(offline.showConsole)

        val online = GamesDisplay.buttons(server("active"), user)
        assertFalse(online.showStart)
        assertTrue(online.showRequestRestart)
        assertFalse(online.showRestart)
        assertFalse(online.showStop)
        assertFalse(online.showConsole)
    }

    /** Before Lexicon grants anything, a signed-in player can only look. */
    @Test
    fun `a viewer gets no buttons at all`() {
        assertFalse(GamesDisplay.buttons(server("inactive"), viewer).any)
        assertFalse(GamesDisplay.buttons(server("active"), viewer).any)
    }

    @Test
    fun `start is shown but disabled, with the reason, when the server cannot start`() {
        val blocked = GamesDisplay.buttons(
            server("inactive", canStart = false).copy(startBlockedReason = "Not enough memory"),
            user,
        )
        assertTrue(blocked.showStart)
        assertFalse(blocked.startEnabled)
        assertEquals("Not enough memory", blocked.startBlockedReason)

        // A server the manager keeps admin-only: a player sees why they cannot press it.
        val adminOnly = GamesDisplay.buttons(server("inactive", public = null), user)
        assertFalse(adminOnly.startEnabled)
        assertEquals("Only an admin can start this server", adminOnly.startBlockedReason)
    }

    @Test
    fun `an admin restarts and stops directly instead of asking`() {
        val online = GamesDisplay.buttons(server("active"), admin)
        assertTrue(online.showRestart)
        assertTrue(online.showStop)
        assertTrue(online.showConsole)
        assertTrue(online.consoleAcceptsCommands)
        assertFalse(online.showRequestRestart)

        // Offline the console is only a log viewer, and an admin can start even an
        // admin-only server.
        val offline = GamesDisplay.buttons(server("inactive", public = null), admin)
        assertTrue(offline.startEnabled)
        assertTrue(offline.showConsole)
        assertFalse(offline.consoleAcceptsCommands)
        assertFalse(offline.showStop)
    }

    @Test
    fun `no start button while a server is already starting`() {
        assertFalse(GamesDisplay.buttons(server("activating"), admin).showStart)
        // Crashed counts as stopped, so it can be started again.
        assertTrue(GamesDisplay.buttons(server("failed"), user).showStart)
    }

    @Test
    fun `the players line tells unknown from nobody`() {
        val mc = (decode(adminStatus) as GamesStatusEvent).servers.first { it.id == "mc-java" }
        assertEquals("1/10 players: steve", GamesDisplay.playersLine(mc))
        assertEquals("players: ?", GamesDisplay.playersLine(server("active")))
        assertEquals("", GamesDisplay.playersLine(server("inactive")))
        assertEquals("0/? players", GamesDisplay.playersLine(server("active").copy(playerCount = 0)))
    }

    @Test
    fun `durations read like the web client's`() {
        val now = 1_000_000L
        assertEquals("", GamesDisplay.ago(null, now))
        assertEquals("", GamesDisplay.ago(0, now))
        assertEquals("5m", GamesDisplay.ago(now - 300, now))
        assertEquals("2h 5m", GamesDisplay.ago(now - 7_500, now))
        assertEquals("1d 4h", GamesDisplay.ago(now - 100_800, now))
        assertEquals("Online · 5m", GamesDisplay.stateLine(server("active").copy(since = now - 300), now))
        assertEquals("Crashed", GamesDisplay.stateLine(server("failed"), now))
    }

    @Test
    fun `online servers sort first, then by name`() {
        val sorted = GamesDisplay.sorted(
            listOf(
                GameServer(id = "b", name = "beta", state = "inactive"),
                GameServer(id = "z", name = "Zeta", state = "active"),
                GameServer(id = "a", name = "Alpha", state = "inactive"),
            ),
        )
        assertEquals(listOf("z", "a", "b"), sorted.map { it.id })
    }

    @Test
    fun `restart requests name the server and who asked`() {
        val status = decode(adminStatus) as GamesStatusEvent
        assertEquals(
            "Minecraft Java: restart asked by bob, alex (lag) · 1m ago",
            GamesDisplay.requestLine(status.restartRequests.single(), status.servers, nowSeconds = 160),
        )
        val anonymous = GameRestartRequest(id = "1", server = "gone", supporters = emptyList())
        assertEquals("gone: restart asked by someone", GamesDisplay.requestLine(anonymous, emptyList(), 0))
    }
}

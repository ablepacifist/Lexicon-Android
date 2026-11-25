package com.alexdyakin.lexicon.data.bridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The game servers on GTW, as the bridge's `game-servers` feature exposes them.
 *
 * Same socket, same flat envelope as everything else in [BridgeFrames]. The bridge
 * relays to the gtw-games manager (127.0.0.1:8790 on GTW), and **Lexicon decides who may
 * do what** — `GAMESERVER_START`, `GAMESERVER_REQUEST_RESTART`, `GAMESERVER_ADMIN`. The
 * client never decides; it only reads [GamesCaps] to know which buttons to draw. The
 * bridge and the manager both check every action again, so a client that drew the wrong
 * button would be refused rather than obeyed.
 *
 * Field names are the manager's snake_case and are kept verbatim with [SerialName].
 * The admin view of a server carries extra fields (`main_pid`, `restarts`, …) that a
 * normal player never receives; unknown keys are ignored by the shared decoder, and the
 * one admin-only field shown ([GameServer.restarts]) is optional.
 */

/** What this player may do. Comes from Lexicon's permissions, computed by the bridge. */
@Serializable
data class GamesCaps(
    val view: Boolean = false,
    val start: Boolean = false,
    val requestRestart: Boolean = false,
    val admin: Boolean = false,
)

/** Per-server switches the manager's definition gives non-admins. */
@Serializable
data class GamePublicRights(
    val start: Boolean = false,
    @SerialName("restart_request") val restartRequest: Boolean = false,
)

@Serializable
data class GameServer(
    val id: String = "",
    val name: String = "",
    val type: String = "",
    @SerialName("join_address") val joinAddress: String? = null,
    /** systemd's word: active, activating, deactivating, inactive, failed, reloading. */
    val state: String = "inactive",
    /** Unix seconds the server entered its current state. */
    val since: Long? = null,
    /**
     * Null, not empty, when the manager cannot tell. That distinction is the point:
     * an online server with an unknown roster reads "players: ?", an offline one shows
     * nothing at all.
     */
    val players: List<String>? = null,
    @SerialName("player_count") val playerCount: Int? = null,
    @SerialName("max_players") val maxPlayers: Int? = null,
    val version: String? = null,
    @SerialName("can_start") val canStart: Boolean = false,
    @SerialName("start_blocked_reason") val startBlockedReason: String? = null,
    val public: GamePublicRights? = null,
    /** systemd restart counter. Admin view only. */
    val restarts: Int? = null,
) {
    val online: Boolean get() = state == "active"
}

@Serializable
data class GameRestartSupporter(val name: String? = null)

/** Players asking for a restart that has not happened yet. */
@Serializable
data class GameRestartRequest(
    val id: String = "",
    /** The server's id, not its name. */
    val server: String = "",
    /** Unix seconds. */
    val created: Long? = null,
    val reason: String? = null,
    val supporters: List<GameRestartSupporter> = emptyList(),
)

// ── inbound ──────────────────────────────────────────────────────────────────

/**
 * The whole picture, re-sent whenever anything changes and on `games_list`.
 *
 * `available` false with an `error` means the bridge has no manager configured or the
 * manager is not answering; the panel shows the error instead of an empty list, so
 * "no servers" and "cannot reach the servers" never look the same.
 */
@Serializable
data class GamesStatusEvent(
    val available: Boolean = false,
    val servers: List<GameServer> = emptyList(),
    @SerialName("restart_requests") val restartRequests: List<GameRestartRequest> = emptyList(),
    @SerialName("mem_available_gb") val memAvailableGb: Double? = null,
    val caps: GamesCaps = GamesCaps(),
    val error: String? = null,
) : BridgeEvent {
    override val type: String get() = "games_status"
}

/** Answer to one of this client's own actions; `message` on success, `error` otherwise. */
@Serializable
data class GamesActionResultEvent(
    val action: String = "",
    val serverId: String? = null,
    val ok: Boolean = false,
    val message: String? = null,
    val error: String? = null,
) : BridgeEvent {
    override val type: String get() = "games_action_result"
    val text: String get() = (if (ok) message else error) ?: if (ok) "Done" else "Failed"
}

@Serializable
data class GamesLogsEvent(
    val serverId: String = "",
    val lines: List<String> = emptyList(),
) : BridgeEvent {
    override val type: String get() = "games_logs"
}

@Serializable
data class GamesCommandResultEvent(
    val serverId: String = "",
    val ok: Boolean = false,
    val output: String? = null,
    val error: String? = null,
) : BridgeEvent {
    override val type: String get() = "games_command_result"
}

/** Broadcast to everyone signed in: "bob asked to restart Palworld". */
@Serializable
data class GamesNoticeEvent(val text: String = "") : BridgeEvent {
    override val type: String get() = "games_notice"
}

// ── outbound ─────────────────────────────────────────────────────────────────
//
// Each builds its JSON by hand, for the reason given on [BridgeCommand]. The bridge
// validates ids against /^[a-z0-9][a-z0-9-]{0,40}$/ (servers) and /^[a-f0-9]{1,32}$/
// (requests) and answers a bad one with an action result, so nothing is checked here.

data class GamesListCommand(private val unused: Unit = Unit) : BridgeCommand {
    override val type: String get() = "games_list"
    override fun toJson() = buildJsonObject { put("type", type) }
}

/** The four plain server actions share a shape: just the server's id. */
data class GamesServerCommand(val action: Action, val serverId: String) : BridgeCommand {
    enum class Action(val wire: String) {
        START("games_start"),
        STOP("games_stop"),
        RESTART("games_restart"),
    }

    override val type: String get() = action.wire
    override fun toJson() = buildJsonObject {
        put("type", type)
        put("serverId", serverId)
    }
}

data class GamesRequestRestartCommand(val serverId: String, val reason: String) : BridgeCommand {
    override val type: String get() = "games_request_restart"
    override fun toJson() = buildJsonObject {
        put("type", type)
        put("serverId", serverId)
        // The bridge trims to 200; sending more would only be thrown away.
        put("reason", reason.trim().take(200))
    }
}

data class GamesDecideRequestCommand(val requestId: String, val approve: Boolean) : BridgeCommand {
    override val type: String get() = if (approve) "games_approve_request" else "games_deny_request"
    override fun toJson() = buildJsonObject {
        put("type", type)
        put("requestId", requestId)
    }
}

data class GamesLogsCommand(val serverId: String, val lines: Int = 200) : BridgeCommand {
    override val type: String get() = "games_logs"
    override fun toJson() = buildJsonObject {
        put("type", type)
        put("serverId", serverId)
        put("lines", lines)
    }
}

data class GamesConsoleCommand(val serverId: String, val command: String) : BridgeCommand {
    override val type: String get() = "games_command"
    override fun toJson() = buildJsonObject {
        put("type", type)
        put("serverId", serverId)
        put("command", command.trim().take(500))
    }
}

// ── display rules ────────────────────────────────────────────────────────────

/**
 * What the panel shows, as plain functions so they can be pinned by tests against the
 * web client's `games.js`. Keep them identical to it: a button that appears on one
 * client and not the other is exactly the confusion a shared backend is meant to avoid.
 */
object GamesDisplay {

    private val stateLabels = mapOf(
        "active" to "Online",
        "activating" to "Starting",
        "deactivating" to "Stopping",
        "inactive" to "Offline",
        "failed" to "Crashed",
        "reloading" to "Reloading",
    )

    fun stateLabel(state: String): String = stateLabels[state] ?: state

    /** Online servers first, then by name — `games.js` sorts the same way. */
    fun sorted(servers: List<GameServer>): List<GameServer> =
        servers.sortedWith(
            compareByDescending<GameServer> { it.online }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
        )

    /** "3m", "2h 5m", "1d 4h" — the web client's `ago()`. Empty when unknown. */
    fun ago(sinceSeconds: Long?, nowSeconds: Long = System.currentTimeMillis() / 1000): String {
        if (sinceSeconds == null || sinceSeconds == 0L) return ""
        val s = (nowSeconds - sinceSeconds).coerceAtLeast(0)
        return when {
            s < 3_600 -> "${s / 60}m"
            s < 86_400 -> "${s / 3_600}h ${(s % 3_600) / 60}m"
            else -> "${s / 86_400}d ${(s % 86_400) / 3_600}h"
        }
    }

    /** "Online · 2h 5m", or just the label when there is no useful duration. */
    fun stateLine(server: GameServer, nowSeconds: Long = System.currentTimeMillis() / 1000): String {
        val label = stateLabel(server.state)
        val age = if (server.online) ago(server.since, nowSeconds) else ""
        return if (age.isEmpty()) label else "$label · $age"
    }

    /** "2/10 players: steve, alex", "players: ?" (online, unknown), or "" (offline, unknown). */
    fun playersLine(server: GameServer): String {
        val count = server.playerCount ?: return if (server.online) "players: ?" else ""
        val base = "$count/${server.maxPlayers ?: "?"} players"
        val names = server.players.orEmpty()
        return if (names.isEmpty()) base else base + ": " + names.joinToString(", ")
    }

    /** Which buttons a server card draws, and whether Start is pressable. */
    data class Buttons(
        val showStart: Boolean,
        val startEnabled: Boolean,
        /** Why Start is greyed out, for the player to read. */
        val startBlockedReason: String?,
        val showRequestRestart: Boolean,
        val showRestart: Boolean,
        val showStop: Boolean,
        val showConsole: Boolean,
        /** Console takes commands only while the server runs; offline it is a log viewer. */
        val consoleAcceptsCommands: Boolean,
    ) {
        val any: Boolean
            get() = showStart || showRequestRestart || showRestart || showStop || showConsole
    }

    fun buttons(server: GameServer, caps: GamesCaps): Buttons {
        val online = server.online
        val showStart = caps.start && !online && server.state != "activating"
        val startEnabled = showStart && server.canStart &&
            (caps.admin || server.public?.start == true)
        return Buttons(
            showStart = showStart,
            startEnabled = startEnabled,
            startBlockedReason = if (showStart && !startEnabled) {
                server.startBlockedReason ?: "Only an admin can start this server"
            } else {
                null
            },
            // Admins restart directly, so they never get the "ask" button.
            showRequestRestart = online && caps.requestRestart && !caps.admin &&
                server.public?.restartRequest == true,
            showRestart = caps.admin && online,
            showStop = caps.admin && online,
            showConsole = caps.admin,
            consoleAcceptsCommands = caps.admin && online,
        )
    }

    /** "Palworld: restart asked by bob, alex (lag) · 4m ago" */
    fun requestLine(
        request: GameRestartRequest,
        servers: List<GameServer>,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): String {
        val name = servers.firstOrNull { it.id == request.server }?.name ?: request.server
        val who = request.supporters.mapNotNull { it.name?.takeIf(String::isNotBlank) }
            .joinToString(", ").ifEmpty { "someone" }
        val reason = request.reason?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        val age = ago(request.created, nowSeconds).let { if (it.isEmpty()) "" else " · $it ago" }
        return "$name: restart asked by $who$reason$age"
    }
}

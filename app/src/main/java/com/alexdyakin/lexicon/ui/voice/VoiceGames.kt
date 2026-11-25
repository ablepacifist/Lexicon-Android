package com.alexdyakin.lexicon.ui.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alexdyakin.lexicon.data.bridge.GameRestartRequest
import com.alexdyakin.lexicon.data.bridge.GameServer
import com.alexdyakin.lexicon.data.bridge.GamesCaps
import com.alexdyakin.lexicon.data.bridge.GamesDisplay
import com.alexdyakin.lexicon.data.bridge.GamesState
import kotlinx.coroutines.delay

/**
 * The GTW game servers panel — the mobile twin of the web client's Servers panel
 * (`mumble-bridge/public/js/games.js`).
 *
 * Which buttons appear comes from [GamesCaps] in `games_status`, via [GamesDisplay], so
 * the two clients draw exactly the same controls for the same player. Nothing here is
 * a permission check: the bridge (Lexicon's GAMESERVER_* permissions) and the gtw-games
 * manager both check every action again.
 */
class GamesCallbacks(
    val refresh: () -> Unit,
    val start: (String) -> Unit,
    val stop: (String) -> Unit,
    val restart: (String) -> Unit,
    val requestRestart: (serverId: String, reason: String) -> Unit,
    val decide: (requestId: String, approve: Boolean) -> Unit,
    val openConsole: (String) -> Unit,
    val closeConsole: () -> Unit,
    val consoleCommand: (String) -> Unit,
    val dismissResult: () -> Unit,
)

/** A confirmation waiting on the player, for the two actions that disconnect people. */
private class PendingConfirm(val title: String, val body: String, val confirmLabel: String, val run: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GamesSheet(
    games: GamesState,
    connected: Boolean,
    onDismiss: () -> Unit,
    actions: GamesCallbacks,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirm by remember { mutableStateOf<PendingConfirm?>(null) }
    var asking by remember { mutableStateOf<GameServer?>(null) }
    var copied by remember { mutableStateOf(false) }

    // The web panel asks afresh every time it opens; the pushed status can be up to a
    // poll interval old.
    LaunchedEffect(Unit) { actions.refresh() }

    // The web client's toasts last five seconds; so do these.
    LaunchedEffect(games.result) {
        if (games.result != null) {
            delay(5_000)
            actions.dismissResult()
        }
    }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            actions.closeConsole()
            onDismiss()
        },
        sheetState = sheetState,
        containerColor = BridgeColors.bgSecondary,
        contentColor = BridgeColors.textNormal,
        dragHandle = { SheetHandle() },
    ) {
        val status = games.status
        val consoleServer = games.consoleServerId?.let { id -> status?.servers?.firstOrNull { it.id == id } }

        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) { SectionLabel("Game servers") }
                status?.memAvailableGb?.let { gb ->
                    Text(
                        "${formatGb(gb)} GB free",
                        color = BridgeColors.textMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }

            games.result?.let { result ->
                Banner(result.text, ok = result.ok, onClick = actions.dismissResult)
            }
            games.notice?.let { Banner(it, ok = true, onClick = {}) }
            if (copied) Banner("Address copied", ok = true, onClick = { copied = false })
            if (!connected) Banner("Not connected to the voice chat.", ok = false, onClick = {})

            when {
                consoleServer != null -> ConsoleView(
                    server = consoleServer,
                    text = games.consoleText,
                    loading = games.consoleLoading,
                    acceptsCommands = GamesDisplay.buttons(consoleServer, status?.caps ?: GamesCaps()).consoleAcceptsCommands,
                    onClose = actions.closeConsole,
                    onCommand = actions.consoleCommand,
                )

                status == null -> Muted("Loading…")

                !status.available -> Muted(status.error ?: "Game servers are not available")

                else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                    if (status.restartRequests.isNotEmpty()) {
                        items(status.restartRequests, key = { "req-" + it.id }) { request ->
                            RequestRow(
                                request = request,
                                servers = status.servers,
                                admin = status.caps.admin,
                                onDecide = actions.decide,
                            )
                        }
                    }
                    if (status.servers.isEmpty()) {
                        item { Muted("No game servers are set up.") }
                    }
                    items(GamesDisplay.sorted(status.servers), key = { it.id }) { server ->
                        ServerCard(
                            server = server,
                            caps = status.caps,
                            onCopied = { copied = true },
                            onStart = { actions.start(server.id) },
                            onRequestRestart = { asking = server },
                            onRestart = {
                                confirm = PendingConfirm(
                                    title = "Restart ${server.name}?",
                                    body = "Players will be disconnected.",
                                    confirmLabel = "Restart now",
                                ) { actions.restart(server.id) }
                            },
                            onStop = {
                                confirm = PendingConfirm(
                                    title = "Stop ${server.name}?",
                                    body = "Everyone playing on it will be disconnected.",
                                    confirmLabel = "Stop",
                                ) { actions.stop(server.id) }
                            },
                            onConsole = { actions.openConsole(server.id) },
                        )
                    }
                }
            }
        }
    }

    confirm?.let { pending ->
        BridgeDialog(
            title = pending.title,
            onDismiss = { confirm = null },
            confirmLabel = pending.confirmLabel,
            danger = true,
            onConfirm = {
                confirm = null
                pending.run()
            },
        ) {
            Text(pending.body, color = BridgeColors.textNormal, fontSize = 14.sp)
        }
    }

    asking?.let { server ->
        var reason by remember(server.id) { mutableStateOf("") }
        BridgeDialog(
            title = "Ask to restart ${server.name}?",
            onDismiss = { asking = null },
            confirmLabel = "Ask",
            danger = false,
            onConfirm = {
                asking = null
                actions.requestRestart(server.id, reason)
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "It runs once nobody is playing, or when an admin approves.",
                    color = BridgeColors.textMuted,
                    fontSize = 13.sp,
                )
                BridgeField(
                    value = reason,
                    onValueChange = { if (it.length <= 200) reason = it },
                    placeholder = "Reason (optional)",
                    monospace = false,
                    onSubmit = {},
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServerCard(
    server: GameServer,
    caps: GamesCaps,
    onCopied: () -> Unit,
    onStart: () -> Unit,
    onRequestRestart: () -> Unit,
    onRestart: () -> Unit,
    onStop: () -> Unit,
    onConsole: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val buttons = GamesDisplay.buttons(server, caps)
    val players = GamesDisplay.playersLine(server)

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(BridgeColors.bgPrimary)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(stateColor(server.state)))
            Spacer(Modifier.width(8.dp))
            Text(
                server.name,
                color = BridgeColors.headerPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                GamesDisplay.stateLine(server),
                color = if (server.state == "failed") BridgeColors.textDanger else BridgeColors.textMuted,
                fontSize = 12.sp,
                maxLines = 1,
            )
        }

        // Indented under the name, as the web card is (17px past the dot).
        val indent = Modifier.padding(start = 17.dp)
        if (players.isNotEmpty()) {
            Text(players, color = BridgeColors.textNormal, fontSize = 13.sp, modifier = indent)
        }
        server.joinAddress?.takeIf { it.isNotBlank() }?.let { address ->
            Row(indent, verticalAlignment = Alignment.CenterVertically) {
                Text("Join: ", color = BridgeColors.textMuted, fontSize = 13.sp)
                Text(
                    address,
                    color = BridgeColors.textLink,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clickable {
                            clipboard.setText(AnnotatedString(address))
                            onCopied()
                        }
                        .padding(vertical = 4.dp),
                )
            }
        }
        if (caps.admin && (server.restarts ?: 0) > 0) {
            Text(
                "systemd restarts: ${server.restarts}",
                color = BridgeColors.textFaint,
                fontSize = 12.sp,
                modifier = indent,
            )
        }
        buttons.startBlockedReason?.let {
            Text(it, color = BridgeColors.textFaint, fontSize = 12.sp, modifier = indent)
        }

        if (buttons.any) {
            // A FlowRow, not a Row: an admin card carries three buttons, and at 320dp
            // with large text they wrap instead of squeezing.
            FlowRow(
                indent.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (buttons.showStart) {
                    BridgeButton("Start", ButtonKind.Primary, enabled = buttons.startEnabled, onClick = onStart)
                }
                if (buttons.showRequestRestart) {
                    BridgeButton("Request restart", ButtonKind.Secondary, onClick = onRequestRestart)
                }
                if (buttons.showRestart) BridgeButton("Restart", ButtonKind.Secondary, onClick = onRestart)
                if (buttons.showStop) BridgeButton("Stop", ButtonKind.Danger, onClick = onStop)
                if (buttons.showConsole) {
                    BridgeButton(
                        if (buttons.consoleAcceptsCommands) "Console" else "Logs",
                        ButtonKind.Secondary,
                        onClick = onConsole,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RequestRow(
    request: GameRestartRequest,
    servers: List<GameServer>,
    admin: Boolean,
    onDecide: (String, Boolean) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(BridgeColors.bgModSubtle)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(GamesDisplay.requestLine(request, servers), color = BridgeColors.textNormal, fontSize = 13.sp)
        if (admin) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BridgeButton("Restart now", ButtonKind.Primary) { onDecide(request.id, true) }
                BridgeButton("Deny", ButtonKind.Secondary) { onDecide(request.id, false) }
            }
        } else {
            Text("Runs once the server is empty", color = BridgeColors.textFaint, fontSize = 12.sp)
        }
    }
}

/**
 * The admin log viewer and console. It takes the sheet's place rather than stacking a
 * second sheet on it, so Back and swipe-down behave the way they do everywhere else.
 */
@Composable
private fun ConsoleView(
    server: GameServer,
    text: String,
    loading: Boolean,
    acceptsCommands: Boolean,
    onClose: () -> Unit,
    onCommand: (String) -> Unit,
) {
    var draft by remember(server.id) { mutableStateOf("") }
    val scroll = rememberScrollState()

    // Follow the tail, as the web console does after every new line.
    LaunchedEffect(text) { scroll.scrollTo(scroll.maxValue) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${server.name}: log",
                color = BridgeColors.headerPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close the log", tint = BridgeColors.interactiveNormal)
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 160.dp, max = 360.dp)
                .background(BridgeColors.bgTertiary)
                .verticalScroll(scroll)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(
                when {
                    loading -> "Loading…"
                    text.isEmpty() -> "(no output)"
                    else -> text
                },
                color = BridgeColors.textNormal,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (acceptsCommands) {
            Row(
                Modifier.fillMaxWidth().background(BridgeColors.inputBg).padding(start = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    BridgeField(
                        value = draft,
                        onValueChange = { if (it.length <= 500) draft = it },
                        placeholder = "Server command",
                        monospace = true,
                        onSubmit = {
                            onCommand(draft)
                            draft = ""
                        },
                        boxed = false,
                    )
                }
                IconButton(
                    onClick = {
                        onCommand(draft)
                        draft = ""
                    },
                    enabled = draft.isNotBlank(),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send command",
                        tint = if (draft.isNotBlank()) BridgeColors.interactiveActive else BridgeColors.interactiveMuted,
                    )
                }
            }
        }
    }
}

/**
 * A banner in the sheet: green for something that worked or a neutral notice, red for a
 * refusal. The bridge's refusals are written for players ("Only an admin can do that"),
 * so they are shown as they come.
 */
@Composable
private fun Banner(text: String, ok: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (ok) BridgeColors.textNormal else BridgeColors.textDanger,
        fontSize = 13.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (ok) BridgeColors.textPositive.copy(alpha = 0.16f) else BridgeColors.textDanger.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun Muted(text: String) {
    Text(text, color = BridgeColors.textMuted, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
}

internal enum class ButtonKind { Primary, Secondary, Danger }

/**
 * The web client's `.btn-small`. The voice screens draw their own controls in the
 * bridge palette rather than the app's buttons, so this does too — but it keeps the
 * app's 48dp touch target through [minimumInteractiveComponentSize], which grows the
 * hit area without growing the button.
 */
@Composable
internal fun BridgeButton(
    label: String,
    kind: ButtonKind,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val fill = when {
        !enabled -> BridgeColors.interactiveMuted
        kind == ButtonKind.Primary -> BridgeColors.brandPrimary
        kind == ButtonKind.Danger -> BridgeColors.textDanger
        else -> BridgeColors.bgAccent
    }
    Box(
        Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(4.dp))
            .background(fill)
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 32.dp)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) Color.White else BridgeColors.textFaint,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun BridgeDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String,
    danger: Boolean,
    onConfirm: () -> Unit,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BridgeColors.bgPrimary,
        titleContentColor = BridgeColors.headerPrimary,
        textContentColor = BridgeColors.textNormal,
        title = { Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
        text = content,
        confirmButton = {
            BridgeButton(confirmLabel, if (danger) ButtonKind.Danger else ButtonKind.Primary, onClick = onConfirm)
        },
        dismissButton = { BridgeButton("Cancel", ButtonKind.Secondary, onClick = onDismiss) },
    )
}

@Composable
private fun BridgeField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    monospace: Boolean,
    onSubmit: () -> Unit,
    boxed: Boolean = true,
) {
    val style = TextStyle(
        color = BridgeColors.textNormal,
        fontSize = if (monospace) 12.sp else 14.sp,
        fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
    )
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(BridgeColors.textNormal),
        keyboardOptions = KeyboardOptions(imeAction = if (monospace) ImeAction.Send else ImeAction.Done),
        keyboardActions = KeyboardActions(onSend = { onSubmit() }, onDone = { onSubmit() }),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (boxed) Modifier.clip(RoundedCornerShape(6.dp)).background(BridgeColors.inputBg) else Modifier,
                    )
                    .padding(horizontal = if (boxed) 12.dp else 0.dp, vertical = 12.dp),
            ) {
                if (value.isEmpty()) Text(placeholder, style = style.copy(color = BridgeColors.textFaint))
                inner()
            }
        },
    )
}

/** The dot colours from the web stylesheet's `.state-*` rules. */
internal fun stateColor(state: String): Color = when (state) {
    "active" -> BridgeColors.textPositive
    "activating", "deactivating" -> BridgeColors.textWarning
    "failed" -> BridgeColors.textDanger
    else -> BridgeColors.interactiveMuted
}

/** "19", or "1.5" — the manager sends whole numbers most of the time. */
private fun formatGb(gb: Double): String =
    if (gb == Math.floor(gb)) gb.toLong().toString() else String.format(java.util.Locale.ROOT, "%.1f", gb)

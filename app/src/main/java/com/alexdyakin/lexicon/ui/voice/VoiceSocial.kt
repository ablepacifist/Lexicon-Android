package com.alexdyakin.lexicon.ui.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alexdyakin.lexicon.data.bridge.BridgeChatState
import com.alexdyakin.lexicon.data.bridge.BridgeNotification
import com.alexdyakin.lexicon.data.bridge.BridgeState
import com.alexdyakin.lexicon.data.bridge.DmConversation
import com.alexdyakin.lexicon.data.bridge.DmLine
import com.alexdyakin.lexicon.data.bridge.MumbleUser
import com.alexdyakin.lexicon.data.bridge.PinnedMessage
import com.alexdyakin.lexicon.data.bridge.WebClient

/**
 * The social surfaces of the bridge client: who is online, direct messages, and the
 * mention feed.
 *
 * The web client puts members in a fixed right-hand column beside the chat. A phone has
 * no room for a second permanent column, so members and mentions become bottom sheets
 * reached from the header, and a direct message takes over the screen the way it does in
 * Discord's own mobile client. The styling stays identical to [VoiceScreen] — same
 * palette, same row shapes — so it reads as one client rather than two.
 */

// ── members ──────────────────────────────────────────────────────────────────

/**
 * Who is online, in two clearly separated groups.
 *
 * Web clients and native Mumble users are genuinely different identities: one is keyed
 * by Lexicon user id, the other by a Mumble session number that changes every time the
 * person reconnects. There is no mapping between them, so they are listed apart rather
 * than merged into one roster that would silently duplicate anybody connected twice.
 *
 * Only web clients can be messaged — a Mumble session has no Lexicon account behind it
 * to deliver a direct message to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MembersSheet(
    state: BridgeChatState,
    onDismiss: () -> Unit,
    onOpenDm: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BridgeColors.bgSecondary,
        contentColor = BridgeColors.textNormal,
        // The app's gold drag handle would be the only medieval thing on a Discord
        // surface, so the sheet supplies its own in the bridge palette.
        dragHandle = { SheetHandle() },
    ) {
        LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            item {
                SectionLabel("Online — " + state.webClients.size)
            }
            items(state.webClients, key = { it.id }) { client ->
                WebMemberRow(client, onClick = { onOpenDm(client.username) })
            }
            if (state.mumbleUsers.isNotEmpty()) {
                item { SectionLabel("Mumble — " + state.mumbleUsers.size) }
                items(state.mumbleUsers, key = { it.session }) { user -> MumbleMemberRow(user) }
            }
            if (state.webClients.isEmpty() && state.mumbleUsers.isEmpty()) {
                item {
                    Text(
                        "Nobody is connected.",
                        color = BridgeColors.textMuted,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun WebMemberRow(client: WebClient, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Avatar(client.username)
            // The web client's presence pip, drawn over the avatar's lower corner.
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(BridgeColors.bgSecondary)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(BridgeColors.textPositive),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            client.username.removePrefix("web_"),
            color = BridgeColors.textNormal,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (client.inVoice) {
            Icon(
                Icons.Filled.VolumeUp,
                contentDescription = "In voice",
                tint = BridgeColors.textPositive,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun MumbleMemberRow(user: MumbleUser) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InitialAvatar(user.name)
        Spacer(Modifier.width(12.dp))
        Text(
            user.name,
            color = BridgeColors.textNormal,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (user.mute || user.selfMute) {
            Icon(
                Icons.Filled.MicOff,
                contentDescription = "Muted",
                tint = BridgeColors.textMuted,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

// ── mentions ─────────────────────────────────────────────────────────────────

/**
 * The mention feed.
 *
 * Opening it marks everything read, which is what the badge means to the person
 * looking at it. The server sends no confirmation for `notifications_read` at all, so
 * the count is cleared locally and never waits on a reply that will not arrive.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MentionsSheet(
    state: BridgeChatState,
    onDismiss: () -> Unit,
    onMarkRead: () -> Unit,
    onOpenChannel: (Int) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(Unit) { onMarkRead() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BridgeColors.bgSecondary,
        contentColor = BridgeColors.textNormal,
        // The app's gold drag handle would be the only medieval thing on a Discord
        // surface, so the sheet supplies its own in the bridge palette.
        dragHandle = { SheetHandle() },
    ) {
        LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            item { SectionLabel("Mentions") }
            if (state.notifications.isEmpty()) {
                item {
                    Text(
                        "No mentions yet.",
                        color = BridgeColors.textMuted,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(state.notifications, key = { it.id }) { notification ->
                MentionRow(notification) {
                    notification.channelId?.let(onOpenChannel)
                    onDismiss()
                }
            }
        }
    }
}

@Composable
private fun MentionRow(notification: BridgeNotification, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Avatar(notification.fromUsername ?: "?")
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    notification.fromUsername ?: "someone",
                    color = BridgeColors.headerPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                notification.channelName?.let {
                    Spacer(Modifier.width(6.dp))
                    Text("in #" + it, color = BridgeColors.textMuted, fontSize = 12.sp)
                }
            }
            notification.messagePreview?.let {
                Text(
                    renderMentions(it),
                    color = BridgeColors.textNormal,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!notification.read) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(BridgeColors.textDanger),
            )
        }
    }
}

// ── direct messages ──────────────────────────────────────────────────────────

/**
 * The direct-message section of the sidebar, above the channel tree.
 *
 * Unread counts here come from the user-scoped DM feed rather than from channel
 * traffic, so unlike channel unreads they stay accurate no matter which channel is
 * currently joined.
 */
@Composable
internal fun DmSection(
    conversations: List<DmConversation>,
    unread: Map<Long, Int>,
    onOpen: (String) -> Unit,
) {
    if (conversations.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        SectionLabel("Direct Messages")
        conversations.sortedByDescending { it.lastMessageAt ?: it.createdAt }.forEach { conversation ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(conversation.otherUsername) }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    conversation.otherUsername,
                    color = BridgeColors.channelIcon,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                val count = unread[conversation.id] ?: 0
                if (count > 0) UnreadPill(count)
            }
        }
    }
}

/**
 * A direct message, taking over the whole screen.
 *
 * Own messages are tinted rather than aligned right: the web client renders DMs in the
 * same left-aligned column as channel chat, and flipping sides here would make the two
 * clients disagree about what a conversation looks like.
 */
@Composable
internal fun DmScreen(
    conversation: DmConversation,
    messages: List<DmLine>,
    loading: Boolean,
    connected: Boolean,
    onClose: () -> Unit,
    onSend: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(BridgeColors.bgPrimary)
            .safeDrawingPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Close",
                    tint = BridgeColors.interactiveNormal,
                )
            }
            Text("@", color = BridgeColors.channelIcon, fontSize = 20.sp)
            Spacer(Modifier.width(6.dp))
            Text(
                conversation.otherUsername,
                color = BridgeColors.headerPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (loading && messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Loading…", color = BridgeColors.textMuted, fontSize = 14.sp)
            }
        } else if (messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "No messages yet.",
                    color = BridgeColors.textMuted,
                    fontSize = 14.sp,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
            ) {
                items(messages, key = { it.id }) { line -> DmRow(line) }
            }
        }

        InputBar(
            enabled = connected,
            placeholder = "Message @" + conversation.otherUsername,
            onSend = onSend,
        )
    }
}

@Composable
private fun DmRow(line: DmLine) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Avatar(line.author)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                line.author,
                color = if (line.fromMe) BridgeColors.brandPrimary else BridgeColors.headerPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(line.text, color = BridgeColors.textNormal, fontSize = 15.sp, lineHeight = 20.sp)
        }
    }
}

// ── pinned messages ──────────────────────────────────────────────────────────

/**
 * The pins on the current channel, read only.
 *
 * Pinning and unpinning are superuser-only on the bridge and fail silently for
 * everyone else, so offering the action here would mostly produce buttons that do
 * nothing. The list is also allowed to be simply absent: the server has no error
 * handling around the query, so a database failure returns no frame at all and this
 * renders as an empty channel rather than as a stuck spinner.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PinsSheet(pins: List<PinnedMessage>, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BridgeColors.bgSecondary,
        contentColor = BridgeColors.textNormal,
        dragHandle = { SheetHandle() },
    ) {
        LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            item { SectionLabel("Pinned messages") }
            if (pins.isEmpty()) {
                item {
                    Text(
                        "Nothing is pinned in this channel.",
                        color = BridgeColors.textMuted,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(pins, key = { it.id }) { pin -> PinRow(pin) }
        }
    }
}

@Composable
private fun PinRow(pin: PinnedMessage) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Avatar(pin.username)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    pin.username,
                    color = BridgeColors.headerPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (pin.pinnedBy.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "pinned by " + pin.pinnedBy,
                        color = BridgeColors.textMuted,
                        fontSize = 12.sp,
                    )
                }
            }
            Text(
                renderMentions(pin.content),
                color = BridgeColors.textNormal,
                fontSize = 15.sp,
                lineHeight = 20.sp,
            )
        }
    }
}

// ── shared bits ──────────────────────────────────────────────────────────────

@Composable
private fun SheetHandle() {
    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .width(32.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(BridgeColors.interactiveMuted),
        )
    }
}

/** Mirrors `.category-label`, reused for every sheet and sidebar section heading. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = BridgeColors.channelIcon,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** The web client's red unread bubble. */
@Composable
internal fun UnreadPill(count: Int) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(BridgeColors.textDanger)
            .padding(horizontal = 6.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99) "99+" else count.toString(),
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** True once the socket can carry a send. */
internal fun BridgeChatState.canSend() = connection is BridgeState.Ready

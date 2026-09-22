package com.alexdyakin.lexicon.ui.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import coil.compose.SubcomposeAsyncImage
import com.alexdyakin.lexicon.data.ApiUrls
import com.alexdyakin.lexicon.data.bridge.BridgeChannel
import com.alexdyakin.lexicon.data.bridge.BridgeChatState
import com.alexdyakin.lexicon.data.bridge.BridgeRepository
import com.alexdyakin.lexicon.data.bridge.BridgeState
import androidx.compose.ui.unit.Dp
import com.alexdyakin.lexicon.data.bridge.ChatAttachment
import com.alexdyakin.lexicon.data.bridge.DmConversation
import com.alexdyakin.lexicon.data.bridge.MumbleUser
import com.alexdyakin.lexicon.data.bridge.WebClient
import com.alexdyakin.lexicon.voice.VoiceService
import com.alexdyakin.lexicon.voice.VoiceSession
import com.alexdyakin.lexicon.voice.VoiceState
import com.alexdyakin.lexicon.data.bridge.ChannelRow
import com.alexdyakin.lexicon.data.bridge.ChatLine
import com.alexdyakin.lexicon.data.bridge.buildChannelRows
import com.alexdyakin.lexicon.data.bridge.MessageOrigin
import com.alexdyakin.lexicon.data.bridge.Reaction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VoiceViewModel @Inject constructor(
    private val repository: BridgeRepository,
    private val voice: VoiceSession,
) : ViewModel() {
    val state: StateFlow<BridgeChatState> = repository.state
    val voiceState: StateFlow<VoiceState> = voice.state

    init {
        repository.start()
    }

    fun selectChannel(id: Int) = repository.selectChannel(id)
    fun send(text: String) = repository.sendMessage(text)
    fun loadOlder() = repository.loadOlder()
    fun dismissNotice() = repository.dismissNotice()
    fun noteTyping() = repository.noteTyping()
    fun stopTyping() = repository.stopTyping()
    fun toggleReaction(messageId: String, emoji: String) =
        repository.toggleReaction(messageId, emoji)
    fun markMentionsRead() = repository.markNotificationsRead()
    fun refreshMentions() = repository.refreshNotifications()
    fun refreshDms() = repository.refreshDms()
    fun openDm(username: String) = repository.openDm(username)
    fun closeDm() = repository.closeDm()
    fun sendDm(text: String) = repository.sendDm(text)
    fun sendImage(uri: Uri, caption: String) = repository.sendImage(uri, caption)

    fun joinVoice(channelId: Int) = voice.start(channelId)
    fun leaveVoice() = voice.stop()
    fun toggleMute() {
        val current = (voice.state.value as? VoiceState.Active)?.muted ?: false
        voice.setMuted(!current)
    }
}

/**
 * Chat against the Mumble bridge, styled to match the web client rather than the rest
 * of this app.
 *
 * The bridge is its own product with an established Discord-dark look, so this screen
 * deliberately does not use [com.alexdyakin.lexicon.ui.components.ScreenScaffold]:
 * parchment and background art would make it read as a different application that
 * happens to show the same messages. Colours are mirrored from the web client's CSS
 * custom properties into [BridgeColors].
 *
 * The web layout is a fixed 240px sidebar beside the chat. That does not fit a phone,
 * so the sidebar becomes a drawer — which is what Discord's own mobile client does —
 * keeping the sidebar styling intact rather than flattening channels into chips.
 *
 * Voice itself is still to come. Contrary to what this file used to claim, that work
 * does **not** involve an Opus codec: the bridge encodes and decodes server-side and
 * the socket carries raw PCM, so what remains is framing, jitter buffering and mixing.
 */
@Composable
fun VoiceScreen(onBack: () -> Unit, viewModel: VoiceViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showMembers by rememberSaveable { mutableStateOf(false) }
    var showMentions by rememberSaveable { mutableStateOf(false) }
    var showPins by rememberSaveable { mutableStateOf(false) }
    val voiceState by viewModel.voiceState.collectAsState()
    val context = LocalContext.current

    // Which room the user asked for, held while the permission dialog is up so the
    // answer can be acted on without re-selecting the channel.
    var pendingVoiceChannel by remember { mutableStateOf<Int?>(null) }

    // The photo picker needs no permission on any supported version — it hands back
    // a grant for the single item chosen, which is all the uploader reads.
    var pendingCaption by remember { mutableStateOf("") }
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val caption = pendingCaption
        pendingCaption = ""
        uri?.let { viewModel.sendImage(it, caption) }
    }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val target = pendingVoiceChannel
        pendingVoiceChannel = null
        if (granted && target != null) {
            VoiceService.start(context)
            viewModel.joinVoice(target)
        }
    }

    fun joinVoice(channelId: Int) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            // Started before the session so the microphone claim is already held when
            // capture opens; the other order can be silenced on the way in.
            VoiceService.start(context)
            viewModel.joinVoice(channelId)
        } else {
            pendingVoiceChannel = channelId
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun leaveVoice() {
        viewModel.leaveVoice()
        VoiceService.stop(context)
    }

    val channelName = state.channels.firstOrNull { it.id == state.selectedChannelId }?.name

    // The conversation list and mention feed are user-scoped rather than channel-
    // scoped, so they are fetched once the socket is usable rather than on every
    // channel switch.
    LaunchedEffect(state.connection) {
        if (state.connection is BridgeState.Ready) {
            viewModel.refreshDms()
            viewModel.refreshMentions()
        }
    }

    // A direct message takes the whole screen; the channel behind it keeps its
    // state, so closing returns to exactly where you were.
    state.openDm?.let { conversation ->
        BackHandler { viewModel.closeDm() }
        DmScreen(
            conversation = conversation,
            messages = state.dmMessages,
            loading = state.loadingDm,
            connected = state.canSend(),
            onClose = viewModel::closeDm,
            onSend = viewModel::sendDm,
        )
        return
    }

    if (showMembers) {
        MembersSheet(
            state = state,
            onDismiss = { showMembers = false },
            onOpenDm = {
                showMembers = false
                viewModel.openDm(it)
            },
        )
    }

    if (showPins) {
        PinsSheet(state.pins, onDismiss = { showPins = false })
    }

    if (showMentions) {
        MentionsSheet(
            state = state,
            onDismiss = { showMentions = false },
            onMarkRead = viewModel::markMentionsRead,
            onOpenChannel = viewModel::selectChannel,
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = BridgeColors.bgSecondary,
                drawerShape = RoundedCornerShape(0.dp),
                modifier = Modifier.width(240.dp),
            ) {
                ChannelSidebar(
                    channels = state.channels,
                    selectedId = state.selectedChannelId,
                    connection = state.connection,
                    webClients = state.webClients,
                    mumbleUsers = state.mumbleUsers,
                    conversations = state.dmConversations,
                    dmUnread = state.dmUnread,
                    onSelect = {
                        viewModel.selectChannel(it)
                        scope.launch { drawerState.close() }
                    },
                    onOpenDm = {
                        scope.launch { drawerState.close() }
                        viewModel.openDm(it)
                    },
                    onJoinVoice = {
                        scope.launch { drawerState.close() }
                        joinVoice(it)
                    },
                )
            }
        },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(BridgeColors.bgPrimary)
                // ScreenScaffold used to supply these; this screen draws its own
                // chrome, and the activity is edge-to-edge, so without this the header
                // sits underneath the status bar.
                .safeDrawingPadding(),
        ) {
            ChatHeader(
                channelName = channelName,
                unreadMentions = state.unreadMentions,
                onMenu = { scope.launch { drawerState.open() } },
                onMembers = { showMembers = true },
                onMentions = { showMentions = true },
                onPins = { showPins = true },
                pinCount = state.pins.size,
                onBack = onBack,
            )

            state.notice?.let { notice ->
                Text(
                    notice,
                    color = BridgeColors.textDanger,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BridgeColors.bgTertiary)
                        .clickable { viewModel.dismissNotice() }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            MessageList(
                state = state,
                modifier = Modifier.weight(1f),
                onLoadOlder = viewModel::loadOlder,
                onToggleReaction = viewModel::toggleReaction,
            )

            VoiceBar(
                state = voiceState,
                channelName = (voiceState as? VoiceState.Active)?.channelId?.let { id ->
                    state.channels.firstOrNull { it.id == id }?.name
                },
                onToggleMute = viewModel::toggleMute,
                onLeave = { leaveVoice() },
            )

            TypingIndicator(state.typing)

            InputBar(
                enabled = state.selectedChannelId != null && state.connection is BridgeState.Ready,
                placeholder = if (channelName != null) "Message #" + channelName else "Message",
                uploading = state.uploading,
                onAttach = { caption ->
                    pendingCaption = caption
                    imagePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                onSend = {
                    viewModel.send(it)
                    viewModel.stopTyping()
                },
                onTyping = viewModel::noteTyping,
                onStopTyping = viewModel::stopTyping,
            )
        }
    }
}

/** Mirrors `.chat-header`: 48dp tall, channel name behind a muted `#`. */
@Composable
private fun ChatHeader(
    channelName: String?,
    unreadMentions: Int,
    onMenu: () -> Unit,
    onMembers: () -> Unit,
    onMentions: () -> Unit,
    onPins: () -> Unit,
    pinCount: Int,
    onBack: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(BridgeColors.bgPrimary)
            .height(48.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onMenu) {
            Icon(Icons.Filled.Menu, contentDescription = "Channels", tint = BridgeColors.interactiveNormal)
        }
        Text("#", color = BridgeColors.channelIcon, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(6.dp))
        Text(
            channelName ?: "no channel",
            color = BridgeColors.headerPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (pinCount > 0) {
            IconButton(onClick = onPins) {
                Icon(
                    Icons.Filled.PushPin,
                    contentDescription = "Pinned messages",
                    tint = BridgeColors.interactiveNormal,
                )
            }
        }
        Box {
            IconButton(onClick = onMentions) {
                Icon(
                    Icons.Filled.Notifications,
                    contentDescription = "Mentions",
                    tint = BridgeColors.interactiveNormal,
                )
            }
            if (unreadMentions > 0) {
                Box(Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 2.dp)) {
                    UnreadPill(unreadMentions)
                }
            }
        }
        IconButton(onClick = onMembers) {
            Icon(
                Icons.Filled.People,
                contentDescription = "Members",
                tint = BridgeColors.interactiveNormal,
            )
        }
        Text(
            "Back",
            color = BridgeColors.interactiveNormal,
            fontSize = 14.sp,
            modifier = Modifier.clickable { onBack() }.padding(8.dp),
        )
    }
}

/**
 * Mirrors the web client's channel tree.
 *
 * Categories are folders, not destinations: tapping one collapses it rather than
 * joining, because you cannot post in Servers any more than you can in Root.
 */
@Composable
private fun ChannelSidebar(
    channels: List<BridgeChannel>,
    selectedId: Int?,
    connection: BridgeState,
    webClients: List<WebClient>,
    mumbleUsers: List<MumbleUser>,
    conversations: List<DmConversation>,
    dmUnread: Map<Long, Int>,
    onSelect: (Int) -> Unit,
    onOpenDm: (String) -> Unit,
    onJoinVoice: (Int) -> Unit,
) {
    var collapsed by rememberSaveable { mutableStateOf(setOf<Int>()) }
    val rows = buildChannelRows(channels, collapsed)

    Column(Modifier.fillMaxHeight().background(BridgeColors.bgSecondary).statusBarsPadding()) {
        Text(
            "Channels",
            color = BridgeColors.headerPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(16.dp),
        )
        LazyColumn(Modifier.weight(1f)) {
            item {
                DmSection(conversations, dmUnread, onOpenDm)
                if (conversations.isNotEmpty()) Spacer(Modifier.height(8.dp))
            }
            items(rows, key = { row ->
                when (row) {
                    is ChannelRow.Category -> "cat-" + row.id
                    is ChannelRow.Channel -> "ch-" + row.channel.id
                }
            }) { row ->
                when (row) {
                    is ChannelRow.Category -> CategoryHeader(row) {
                        collapsed = if (row.id in collapsed) collapsed - row.id else collapsed + row.id
                    }

                    // A voice room is a place to talk, not to read: there is no
                    // message history behind one, so selecting it would show an
                    // empty channel.
                    is ChannelRow.Channel -> Column {
                        ChannelItem(
                            row = row,
                            active = row.channel.id == selectedId,
                            onClick = {
                                if (row.isVoice) onJoinVoice(row.channel.id)
                                else onSelect(row.channel.id)
                            },
                        )
                        if (row.isVoice) {
                            VoiceOccupants(
                                channelId = row.channel.id,
                                webClients = webClients,
                                mumbleUsers = mumbleUsers,
                            )
                        }
                    }
                }
            }
        }
        UserPanel(connection)
    }
}

/**
 * Who is sitting in a voice room, listed underneath it as Discord does.
 *
 * Both identity spaces are drawn together here, because to a person reading the
 * sidebar they are simply the people in the room: web clients report their room in
 * `voiceChannelId`, while native Mumble users are in it by virtue of `channelId`.
 * That is the one place merging them is right — it says nothing about who they are,
 * only where they are.
 */
@Composable
private fun VoiceOccupants(
    channelId: Int,
    webClients: List<WebClient>,
    mumbleUsers: List<MumbleUser>,
) {
    // A web user in voice appears in BOTH rosters: once as a web client, and again as
    // the Mumble connection the bridge opens on their behalf, which authenticates as
    // "web_<username>". Stripping that prefix from both sides collapses the two into
    // the one person they actually are.
    val names = buildList {
        webClients.filter { it.inVoice && it.voiceChannelId == channelId }
            .forEach { add(it.username.removePrefix("web_")) }
        mumbleUsers.filter { it.channelId == channelId }
            .forEach { add(it.name.removePrefix("web_")) }
    }.distinct().sorted()

    names.forEach { name ->
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 40.dp, end = 8.dp, top = 1.dp, bottom = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(20.dp)) { InitialAvatar(name, size = 20.dp) }
            Spacer(Modifier.width(8.dp))
            Text(
                name,
                color = BridgeColors.textMuted,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Mirrors .category-header / .category-label: 12sp bold uppercase, muted. */
@Composable
private fun CategoryHeader(row: ChannelRow.Category, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (row.collapsed) Icons.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = BridgeColors.channelIcon,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(2.dp))
        Text(
            row.name.uppercase(),
            color = BridgeColors.channelIcon,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Mirrors .channel-item, including the speaker icon on voice rooms. */
@Composable
private fun ChannelItem(row: ChannelRow.Channel, active: Boolean, onClick: () -> Unit) {
    val tint = if (active) BridgeColors.interactiveActive else BridgeColors.channelIcon
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = if (row.indented) 16.dp else 8.dp, end = 8.dp)
            .padding(vertical = 1.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (active) BridgeColors.bgModStrong else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.isVoice) {
            Icon(
                Icons.Filled.VolumeUp,
                contentDescription = "Voice channel",
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
        } else {
            Text("#", color = tint, fontSize = 16.sp)
        }
        Spacer(Modifier.width(6.dp))
        Text(
            row.channel.name,
            color = tint,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Stands in for the web client's user panel at the foot of the sidebar. */
@Composable
private fun UserPanel(connection: BridgeState) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(BridgeColors.bgTertiary)
            .height(52.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val label = when (connection) {
            is BridgeState.Ready -> connection.username
            BridgeState.Connecting -> "Connecting…"
            BridgeState.Authenticating -> "Signing in…"
            BridgeState.Disconnected -> "Offline"
            is BridgeState.Failed -> connection.reason
        }
        val tint = when (connection) {
            is BridgeState.Ready -> BridgeColors.textPositive
            is BridgeState.Failed -> BridgeColors.textDanger
            else -> BridgeColors.textMuted
        }
        Box(Modifier.size(8.dp).clip(CircleShape).background(tint))
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            color = BridgeColors.textNormal,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MessageList(
    state: BridgeChatState,
    modifier: Modifier,
    onLoadOlder: () -> Unit,
    onToggleReaction: (String, String) -> Unit,
) {
    val listState = rememberLazyListState()

    // Follow new messages, but only when already near the bottom, so reading back
    // through history is not yanked away by an arrival.
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            val last = state.messages.lastIndex
            if (listState.firstVisibleItemIndex >= last - 4) listState.animateScrollToItem(last)
        }
    }

    when {
        state.selectedChannelId == null -> Box(modifier.fillMaxWidth(), Alignment.Center) {
            Text("Open the channel list to start reading.", color = BridgeColors.textMuted, fontSize = 14.sp)
        }

        state.messages.isEmpty() && state.loadingHistory -> Box(modifier.fillMaxWidth(), Alignment.Center) {
            Text("Loading messages…", color = BridgeColors.textMuted, fontSize = 14.sp)
        }

        state.messages.isEmpty() -> Box(modifier.fillMaxWidth(), Alignment.Center) {
            Text("No messages yet.", color = BridgeColors.textMuted, fontSize = 14.sp)
        }

        else -> LazyColumn(
            state = listState,
            modifier = modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            item {
                Text(
                    if (state.loadingHistory) "Loading older…" else "Load older messages",
                    color = BridgeColors.textLink,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !state.loadingHistory) { onLoadOlder() }
                        .padding(16.dp),
                )
            }
            itemsIndexed(state.messages, key = { _, line -> line.id }) { index, line ->
                // The signature Discord behaviour: a run of messages from one author
                // shows the avatar and header once, and the rest sit flush beneath.
                val previous = state.messages.getOrNull(index - 1)
                val grouped = previous != null &&
                    previous.username == line.username &&
                    previous.origin == line.origin
                MessageRow(
                    line = line,
                    showHeader = !grouped,
                    reactions = state.reactions[line.id].orEmpty(),
                    onToggleReaction = { onToggleReaction(line.id, it) },
                )
            }
        }
    }
}

/**
 * Mirrors `.message`: 16dp left padding, a 40dp avatar gutter, 16dp gap, and a 16dp
 * top margin only on the first message of a group.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    line: ChatLine,
    showHeader: Boolean,
    reactions: List<Reaction>,
    onToggleReaction: (String) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = { picking = true })
            .padding(top = if (showHeader) 16.dp else 0.dp)
            .padding(start = 16.dp, end = 48.dp, top = 2.dp, bottom = 2.dp),
    ) {
        Box(Modifier.size(40.dp)) {
            if (showHeader) Avatar(line.username)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            if (showHeader) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        line.username.removePrefix("web_"),
                        color = BridgeColors.headerPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (line.origin != MessageOrigin.WEB) {
                        Spacer(Modifier.width(6.dp))
                        OriginTag(if (line.origin == MessageOrigin.MUMBLE) "MUMBLE" else "DISCORD")
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(shortTime(line.timestamp), color = BridgeColors.textFaint, fontSize = 12.sp)
                    // History rows carry the pin flag directly, so a pinned message
                    // is marked without consulting the separate pin list.
                    if (line.isPinned) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Filled.PushPin,
                            contentDescription = "Pinned",
                            tint = BridgeColors.textMuted,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
            }
            Text(
                renderMentions(line.text),
                color = BridgeColors.textNormal,
                fontSize = 15.sp,
                lineHeight = 20.sp,
            )
            line.attachment?.let { ChatImage(it) }
            if (picking) {
                Spacer(Modifier.height(4.dp))
                EmojiPicker(
                    onPick = {
                        onToggleReaction(it)
                        picking = false
                    },
                    onDismiss = { picking = false },
                )
            }
            if (reactions.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                ReactionRow(reactions, onToggleReaction)
            }
        }
    }
}

/**
 * An image attached to a message.
 *
 * The thumbnail is shown rather than the original: the full-size file can be several
 * megabytes, and it is only worth fetching when someone opens it. Every URL from the
 * bridge is host-relative, so it is resolved against the bridge rather than Lexicon.
 *
 * The declared width and height set the aspect ratio up front, so the list does not
 * jump as images decode.
 */
@Composable
private fun ChatImage(attachment: ChatAttachment) {
    val url = attachment.previewUrl ?: return
    val ratio = if (attachment.width != null && attachment.height != null &&
        attachment.width > 0 && attachment.height > 0
    ) {
        attachment.width.toFloat() / attachment.height.toFloat()
    } else {
        null
    }

    Spacer(Modifier.height(6.dp))
    SubcomposeAsyncImage(
        model = absoluteBridgeUrl(url),
        contentDescription = attachment.originalFilename ?: "image",
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .widthIn(max = 260.dp)
            .then(if (ratio != null) Modifier.aspectRatio(ratio) else Modifier)
            .clip(RoundedCornerShape(8.dp))
            .background(BridgeColors.bgTertiary),
        error = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    attachment.originalFilename ?: "image unavailable",
                    color = BridgeColors.textMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(12.dp),
                )
            }
        },
        loading = {},
    )
}

/** Bridge URLs arrive host-relative; Coil needs an absolute one. */
private fun absoluteBridgeUrl(url: String): String =
    if (url.startsWith("http")) url else ApiUrls.VOICE.trimEnd('/') + "/" + url.trimStart('/')

/**
 * Real avatar when Lexicon has one, otherwise the initial on a stable per-name colour.
 * The endpoint is the same one `ProfileApi` uses.
 */
@Composable
internal fun Avatar(username: String) {
    val clean = username.removePrefix("web_")
    SubcomposeAsyncImage(
        model = ApiUrls.LEXICON + "api/avatar/" + clean,
        contentDescription = null,
        modifier = Modifier.size(40.dp).clip(CircleShape),
        error = { InitialAvatar(clean) },
        loading = { InitialAvatar(clean) },
    )
}

@Composable
internal fun InitialAvatar(name: String, size: Dp = 40.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(BridgeColors.avatarColor(name)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.firstOrNull()?.uppercase() ?: "?",
            color = Color.White,
            fontSize = if (size >= 40.dp) 16.sp else 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** `.mention` in the web client: tinted so an @name stands out of the body text. */
internal fun renderMentions(text: String) = buildAnnotatedString {
    var index = 0
    Regex("@\\w{1,32}").findAll(text).forEach { match ->
        append(text.substring(index, match.range.first))
        withStyle(SpanStyle(color = BridgeColors.textLink, fontWeight = FontWeight.Medium)) {
            append(match.value)
        }
        index = match.range.last + 1
    }
    if (index < text.length) append(text.substring(index))
}

@Composable
private fun OriginTag(text: String) {
    Text(
        text,
        color = BridgeColors.interactiveActive,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(BridgeColors.brandPrimary)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

/** Mirrors `#input-bar` + `.input-wrapper`: a rounded #383a40 well, 24dp bottom gutter. */
@Composable
internal fun InputBar(
    enabled: Boolean,
    placeholder: String,
    onSend: (String) -> Unit,
    onTyping: () -> Unit = {},
    onStopTyping: () -> Unit = {},
    uploading: Boolean = false,
    onAttach: ((String) -> Unit)? = null,
) {
    var draft by rememberSaveable { mutableStateOf("") }

    fun submit() {
        if (draft.isNotBlank()) {
            onSend(draft)
            draft = ""
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp, top = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(BridgeColors.inputBg)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onAttach != null) {
            // The draft travels with the image as its caption, matching the web
            // client, where whatever is typed is attached to the upload.
            IconButton(
                onClick = {
                    onAttach(draft)
                    draft = ""
                },
                enabled = enabled && !uploading,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    if (uploading) Icons.Filled.HourglassEmpty else Icons.Filled.AddPhotoAlternate,
                    contentDescription = if (uploading) "Uploading" else "Attach image",
                    tint = BridgeColors.interactiveNormal,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        Box(Modifier.weight(1f).padding(vertical = 11.dp)) {
            if (draft.isEmpty()) {
                Text(placeholder, color = BridgeColors.textMuted, fontSize = 15.sp)
            }
            BasicTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    if (it.isBlank()) onStopTyping() else onTyping()
                },
                enabled = enabled,
                textStyle = TextStyle(color = BridgeColors.textNormal, fontSize = 15.sp),
                cursorBrush = SolidColor(BridgeColors.textNormal),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        IconButton(onClick = ::submit, enabled = enabled && draft.isNotBlank()) {
            Icon(
                Icons.Filled.Send,
                contentDescription = "Send",
                tint = if (enabled && draft.isNotBlank()) BridgeColors.interactiveHover
                else BridgeColors.interactiveMuted,
            )
        }
    }
}

/**
 * Pulls `HH:mm` out of the timestamp by hand.
 *
 * Two different formats arrive: Lexicon's `LocalDateTime` (`2026-08-16T14:43:48.835365`,
 * no zone) and the bridge's own `toISOString()` (`2026-08-16T14:43:48.835Z`). Both put
 * the clock time in the same place, and `java.time` is unavailable at this minSdk
 * without desugaring, so a substring is both sufficient and cheaper than a parser.
 */
internal fun shortTime(timestamp: String): String {
    val t = timestamp.indexOf('T')
    if (t < 0 || timestamp.length < t + 6) return ""
    return timestamp.substring(t + 1, t + 6)
}

/**
 * A short list of reactions to choose from, opened by long-pressing a message.
 *
 * The bridge accepts any unicode emoji or shortcode up to 32 characters, but a full
 * picker on a phone is a screen of its own; these are the six the web client offers
 * as quick reactions, so the two clients produce the same set in practice.
 */
@Composable
private fun EmojiPicker(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(BridgeColors.bgFloating)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QUICK_REACTIONS.forEach { emoji ->
            Text(
                emoji,
                fontSize = 20.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onPick(emoji) }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
        Text(
            "\u00d7",
            color = BridgeColors.textMuted,
            fontSize = 18.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { onDismiss() }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

private val QUICK_REACTIONS = listOf("\uD83D\uDC4D", "\u2764\uFE0F", "\uD83D\uDE02", "\uD83C\uDF89", "\uD83D\uDE2E", "\uD83D\uDE22")

/**
 * Mirrors the web client's reaction pills. Tapping toggles your own reaction; the
 * count updates locally first because the sender does not reliably receive its own
 * broadcast.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReactionRow(reactions: List<Reaction>, onToggle: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        reactions.forEach { reaction ->
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(BridgeColors.bgModSubtle)
                    .clickable { onToggle(reaction.emoji) }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(reaction.emoji, fontSize = 13.sp)
                Spacer(Modifier.width(4.dp))
                Text(
                    reaction.count.toString(),
                    color = BridgeColors.textMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * The web client's typing line. Reserves no height when idle so the composer does
 * not jump as people start and stop.
 */
@Composable
private fun TypingIndicator(typing: Set<String>) {
    if (typing.isEmpty()) return
    val names = typing.map { it.removePrefix("web_") }.sorted()
    val label = when {
        names.size == 1 -> names[0] + " is typing…"
        names.size == 2 -> names[0] + " and " + names[1] + " are typing…"
        else -> "several people are typing…"
    }
    Text(
        label,
        color = BridgeColors.textMuted,
        fontSize = 12.sp,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, bottom = 2.dp),
    )
}
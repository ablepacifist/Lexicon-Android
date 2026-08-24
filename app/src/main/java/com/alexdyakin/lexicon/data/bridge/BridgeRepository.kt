package com.alexdyakin.lexicon.data.bridge

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** A chat line ready to display, from any of the three origins. */
data class ChatLine(
    val id: String,
    val username: String,
    val text: String,
    val timestamp: String,
    val origin: MessageOrigin,
    val isPinned: Boolean = false,
    /** Set on IMAGE and GIF messages. */
    val attachment: ChatAttachment? = null,
    val replyToId: String? = null,
    /** Set only for a line this device sent that the server has not echoed yet. */
    val pending: Boolean = false,
)

/** One rendered direct message. */
data class DmLine(
    val id: String,
    val author: String,
    val text: String,
    val timestamp: String,
    val fromMe: Boolean,
)

data class BridgeChatState(
    val connection: BridgeState = BridgeState.Disconnected,
    val channels: List<BridgeChannel> = emptyList(),
    val selectedChannelId: Int? = null,
    val messages: List<ChatLine> = emptyList(),
    val loadingHistory: Boolean = false,
    /** Web/mobile clients, keyed by "web_<userId>". */
    val webClients: List<WebClient> = emptyList(),
    /** Native Mumble users, keyed by session — a separate identity space. */
    val mumbleUsers: List<MumbleUser> = emptyList(),
    /** Usernames currently typing in the selected channel. */
    val typing: Set<String> = emptySet(),
    /** messageId -> reactions on it. */
    val reactions: Map<String, List<Reaction>> = emptyMap(),
    val pins: List<PinnedMessage> = emptyList(),
    val notifications: List<BridgeNotification> = emptyList(),
    /** Direct-message conversations. */
    val dmConversations: List<DmConversation> = emptyList(),
    /** conversationId -> unread count, from the user-scoped DM feed. */
    val dmUnread: Map<Long, Int> = emptyMap(),
    val openDm: DmConversation? = null,
    val dmMessages: List<DmLine> = emptyList(),
    val loadingDm: Boolean = false,
    /** An image is on its way to the server. */
    val uploading: Boolean = false,
    val unreadMentions: Int = 0,
    /** Transient; the bridge sends no correlation id so this cannot be attributed. */
    val notice: String? = null,
) {
    /** Our own presence key, or null before authentication. */
    val myWebClientId: String?
        get() = (connection as? BridgeState.Ready)?.let { "web_" + it.userId }

    /**
     * Adds us to a roster that forgot us.
     *
     * The bridge can drop our presence entry when a stale close handler fires after
     * a reconnect, so a later `web_users` can legitimately arrive without us in
     * it. We are demonstrably connected, so we are put back.
     */
    fun withSelf(clients: List<WebClient>): List<WebClient> {
        val ready = connection as? BridgeState.Ready ?: return clients
        val id = "web_" + ready.userId
        if (clients.any { it.id == id }) return clients
        return clients + WebClient(
            id = id,
            username = ready.username,
            channelId = selectedChannelId ?: 0,
        )
    }
}

/**
 * Turns the bridge's frame stream into something a screen can render.
 *
 * Two behaviours here are dictated by the server rather than chosen:
 *
 *  - **`server_state` arrives twice** — once before authentication with restricted
 *    channels filtered out, then again afterwards carrying only the channels that just
 *    became visible. Channels are therefore merged by id, never replaced, or the second
 *    frame would wipe the first.
 *  - **`join_channel` decides what you receive.** Unjoined, the bridge sends broadcasts
 *    for every unrestricted channel; joined, only that one, and restricted channels are
 *    delivered *only* when joined. Since the screen shows one channel at a time, the
 *    repository joins on selection. The cost is that other channels go quiet, so live
 *    unread counts for them are not possible over this socket — those come from the
 *    user-scoped notification feed instead.
 */
@Singleton
class BridgeRepository @Inject constructor(
    private val connection: BridgeConnection,
    private val uploader: ChatUploader,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(BridgeChatState())
    val state: StateFlow<BridgeChatState> = _state.asStateFlow()

    private var started = false
    private var lastTypingSentAt = 0L
    private val typingExpiry = mutableMapOf<String, Job>()

    fun start() {
        if (started) return
        started = true

        scope.launch {
            connection.state.collect { connectionState ->
                _state.update { current ->
                    val next = current.copy(connection = connectionState)
                    // Seeded from our own authenticated socket rather than waiting
                    // for the roster frame, which a stale close handler can leave us
                    // out of. Being connected is sufficient proof of being online.
                    if (connectionState is BridgeState.Ready) {
                        next.copy(webClients = next.withSelf(next.webClients))
                    } else {
                        next
                    }
                }
                // Nothing survives a reconnect server-side, so re-establish on Ready.
                if (connectionState is BridgeState.Ready) reestablish()
            }
        }
        scope.launch { connection.events.collect(::onEvent) }
        connection.start()
    }

    fun stop() {
        connection.stop()
    }

    fun selectChannel(channelId: Int) {
        if (_state.value.selectedChannelId == channelId) return
        _state.update {
            it.copy(selectedChannelId = channelId, messages = emptyList(), loadingHistory = true)
        }
        scope.launch {
            connection.send(JoinChannelCommand(channelId))
            connection.send(GetHistoryCommand(channelId))
            connection.send(GetPinnedMessagesCommand(channelId))
        }
    }

    fun sendMessage(text: String) {
        val channelId = _state.value.selectedChannelId ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        scope.launch { connection.send(SendTextCommand(channelId, trimmed)) }
    }

    /** Pages further back, using the oldest held message as the cursor. */
    fun loadOlder() {
        val channelId = _state.value.selectedChannelId ?: return
        val oldest = _state.value.messages.firstOrNull()?.timestamp ?: return
        _state.update { it.copy(loadingHistory = true) }
        scope.launch { connection.send(GetHistoryCommand(channelId, before = oldest)) }
    }

    /**
     * Announces typing, self-throttled to the server's own three second window.
     *
     * The bridge drops anything faster and expires the indicator after eight seconds,
     * so this refreshes on a shorter cadence than the expiry to keep it alive while
     * someone is still writing.
     */
    fun noteTyping() {
        val channelId = _state.value.selectedChannelId ?: return
        val now = System.currentTimeMillis()
        if (now - lastTypingSentAt < TYPING_THROTTLE_MS) return
        lastTypingSentAt = now
        scope.launch { connection.send(TypingStartCommand(channelId)) }
    }

    fun stopTyping() {
        val channelId = _state.value.selectedChannelId ?: return
        lastTypingSentAt = 0
        scope.launch { connection.send(TypingStopCommand(channelId)) }
    }

    /**
     * Toggles a reaction, updating locally first.
     *
     * The sender does not reliably receive its own reaction_update broadcast — it
     * only reaches clients whose joined channel matches — so waiting for the echo
     * would leave the tap looking dead.
     */
    fun toggleReaction(messageId: String, emoji: String) {
        val channelId = _state.value.selectedChannelId ?: return
        val me = (_state.value.connection as? BridgeState.Ready)?.username ?: return
        val existing = _state.value.reactions[messageId].orEmpty()
        val mine = existing.firstOrNull { it.emoji == emoji }?.users?.contains(me) == true

        _state.update { current ->
            val updated = applyLocalReaction(existing, emoji, me, add = !mine)
            current.copy(reactions = current.reactions + (messageId to updated))
        }
        scope.launch {
            if (mine) connection.send(ReactionRemoveCommand(messageId, emoji, channelId))
            else connection.send(ReactionAddCommand(messageId, emoji, channelId))
        }
    }

    fun refreshNotifications() {
        scope.launch { connection.send(GetNotificationsCommand()) }
    }

    fun markNotificationsRead() {
        _state.update { it.copy(unreadMentions = 0, notifications = it.notifications.map { n -> n.copy(isRead = 1) }) }
        scope.launch { connection.send(NotificationsReadCommand()) }
    }

    /**
     * Uploads an image and then posts it.
     *
     * Two steps against two different transports: the bytes go over REST to the
     * bridge, which stores them in Lexicon and returns the ids, and only then does
     * the socket frame go out. A failed upload therefore never produces a message
     * pointing at a file that does not exist.
     */
    fun sendImage(uri: android.net.Uri, caption: String = "") {
        val channelId = _state.value.selectedChannelId ?: return
        val ready = _state.value.connection as? BridgeState.Ready ?: return
        // The bridge keys the upload by Lexicon user id; without one it has nobody to
        // attribute the file to, so there is nothing useful to send.
        val userId = ready.userId ?: return
        _state.update { it.copy(uploading = true) }

        scope.launch {
            val result = uploader.upload(uri, userId, ready.username, channelId)
            _state.update { it.copy(uploading = false) }
            result
                .onSuccess { file ->
                    connection.send(
                        SendImageCommand(
                            channelId = channelId,
                            fileId = file.id,
                            fileUrl = file.url,
                            thumbnailUrl = file.thumbnailUrl,
                            originalFilename = file.originalFilename,
                            mimeType = file.mimeType,
                            width = file.width,
                            height = file.height,
                            caption = caption.trim(),
                        ),
                    )
                }
                .onFailure { error ->
                    _state.update { it.copy(notice = error.message ?: "Upload failed.") }
                }
        }
    }

    fun refreshDms() {
        scope.launch { connection.send(DmConversationsCommand()) }
    }

    /**
     * Opens a conversation by username.
     *
     * `dm_open` takes `username` while `dm_send` takes `toUsername` for the
     * same thing; the commands keep the server's spelling rather than papering over
     * it. History is chained from the `dm_opened` reply, because only the server
     * knows the conversation id for a pair that has never spoken.
     */
    fun openDm(username: String) {
        _state.update { it.copy(dmMessages = emptyList(), loadingDm = true) }
        scope.launch { connection.send(DmOpenCommand(username)) }
    }

    fun closeDm() {
        _state.update { it.copy(openDm = null, dmMessages = emptyList(), loadingDm = false) }
    }

    fun sendDm(text: String) {
        val to = _state.value.openDm?.otherUsername ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        scope.launch { connection.send(DmSendCommand(to, trimmed)) }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    // ── internals ────────────────────────────────────────────────────────────

    private suspend fun reestablish() {
        connection.send(GetNotificationsCommand())
        connection.send(DmConversationsCommand())
        val channelId = _state.value.selectedChannelId ?: return
        connection.send(JoinChannelCommand(channelId))
        connection.send(GetHistoryCommand(channelId))
        connection.send(GetPinnedMessagesCommand(channelId))
    }

    private fun onEvent(event: BridgeEvent) {
        when (event) {
            is ServerStateEvent -> _state.update { it.copy(channels = merge(it.channels, event.channels)) }

            is ChannelUpdateEvent ->
                _state.update { it.copy(channels = merge(it.channels, listOf(event.channel))) }

            is ChannelRemoveEvent -> _state.update { current ->
                current.copy(channels = current.channels.filterNot { it.id == event.channelId })
            }

            is HistoryEvent -> onHistory(event)

            is TextEvent -> onText(event)

            is ImageEvent -> onImage(event)

            is WebUsersEvent -> _state.update { it.copy(webClients = it.withSelf(event.webClients)) }

            is WebUserJoinEvent -> _state.update { current ->
                current.copy(
                    webClients = current.webClients.filterNot { it.id == event.webClient.id } +
                        event.webClient,
                )
            }

            // A leave for ourselves is always stale. Reconnecting fast enough that
            // the old socket's close handler runs after the new one authenticated
            // makes the bridge delete the new presence entry and announce that we
            // left — while we are sitting on an open, authenticated socket. Since
            // our own connection is the evidence, the frame is dropped rather than
            // emptying the member list.
            is WebUserLeaveEvent -> _state.update { current ->
                if (event.id == current.myWebClientId) {
                    Log.w(TAG, "stale self-leave dropped: ${event.id}")
                    current
                }
                else current.copy(webClients = current.webClients.filterNot { it.id == event.id })
            }

            is VoiceStateEvent -> _state.update { current ->
                current.copy(
                    webClients = current.webClients.map {
                        if (it.id == event.id) {
                            it.copy(inVoice = event.inVoice, voiceChannelId = event.voiceChannelId)
                        } else {
                            it
                        }
                    },
                )
            }

            is UserUpdateEvent -> _state.update { current ->
                current.copy(
                    mumbleUsers = current.mumbleUsers.filterNot { it.session == event.user.session } +
                        event.user,
                )
            }

            is UserRemoveEvent -> _state.update { current ->
                current.copy(mumbleUsers = current.mumbleUsers.filterNot { it.session == event.session })
            }

            is TypingEvent -> onTyping(event)

            is ReactionUpdateEvent -> _state.update { current ->
                current.copy(reactions = current.reactions + (event.messageId to event.reactions))
            }

            is PinnedMessagesEvent -> _state.update { it.copy(pins = event.pins) }

            is PinAddedEvent -> _state.update { current ->
                current.copy(pins = current.pins.filterNot { it.messageId == event.pin.messageId } + event.pin)
            }

            is PinRemovedEvent -> _state.update { current ->
                current.copy(pins = current.pins.filterNot { it.messageId == event.messageId })
            }

            is DmConversationsEvent -> _state.update { it.copy(dmConversations = event.conversations) }

            is DmOpenedEvent -> onDmOpened(event)

            is DmHistoryEvent -> onDmHistory(event)

            is DmMessageEvent -> onDmMessage(event)

            is DmUnreadCountsEvent -> _state.update { current ->
                current.copy(dmUnread = event.unreads.associate { it.conversationId to it.unreadCount })
            }

            is DmUnreadUpdateEvent -> _state.update { current ->
                current.copy(dmUnread = current.dmUnread + (event.conversationId to event.unreadCount))
            }

            is NotificationsEvent -> _state.update {
                it.copy(notifications = event.notifications, unreadMentions = event.unreadCount)
            }

            // The live push has a different shape from a list row, so it only bumps the
            // count; the list refreshes on demand.
            is NotificationPushEvent -> _state.update { it.copy(unreadMentions = it.unreadMentions + 1) }

            // No correlation id exists, so this cannot be tied to a request. Show it
            // and move on rather than failing a specific call.
            is ErrorEvent -> _state.update { it.copy(notice = event.message) }

            else -> Unit
        }
    }

    /**
     * The server answers `dm_open` with the conversation id, which is the only way to
     * learn it for a pair that has never spoken, so history is requested from here.
     */
    private fun onDmOpened(event: DmOpenedEvent) {
        val conversation = DmConversation(
            id = event.conversationId,
            otherUsername = event.otherUsername,
            otherUserId = event.otherUserId,
        )
        _state.update { current ->
            current.copy(
                openDm = conversation,
                loadingDm = true,
                dmUnread = current.dmUnread - event.conversationId,
                dmConversations = current.dmConversations
                    .filterNot { it.id == conversation.id } + conversation,
            )
        }
        scope.launch {
            connection.send(DmHistoryCommand(event.conversationId))
            connection.send(DmMarkReadCommand(event.conversationId))
        }
    }

    /**
     * A failed lookup returns an empty message list, which is indistinguishable from
     * a conversation nobody has written in yet. Both render as nothing to show; there
     * is no information available to tell them apart.
     */
    private fun onDmHistory(event: DmHistoryEvent) {
        if (event.conversationId != _state.value.openDm?.id) return
        val me = (_state.value.connection as? BridgeState.Ready)?.username
        _state.update { current ->
            current.copy(
                loadingDm = false,
                dmMessages = event.messages.map {
                    DmLine(
                        id = it.id,
                        author = it.fromUsername,
                        text = it.content,
                        timestamp = it.timestamp,
                        fromMe = it.fromUsername == me,
                    )
                },
            )
        }
    }

    /**
     * Appends a live direct message.
     *
     * The id is null when the socket delivered the message but the Lexicon store call
     * behind it failed, so a synthetic key stands in for list identity — the message
     * did arrive, it simply has no row to refer back to.
     */
    private fun onDmMessage(event: DmMessageEvent) {
        val me = (_state.value.connection as? BridgeState.Ready)?.username
        if (event.conversationId != _state.value.openDm?.id) {
            if (event.fromUsername != me) {
                _state.update { current ->
                    val previous = current.dmUnread[event.conversationId] ?: 0
                    current.copy(
                        dmUnread = current.dmUnread + (event.conversationId to previous + 1),
                    )
                }
            }
            return
        }
        val line = DmLine(
            id = event.id ?: "local-" + System.nanoTime(),
            author = event.fromUsername,
            text = event.content,
            timestamp = event.timestamp,
            fromMe = event.fromUsername == me,
        )
        _state.update { current ->
            if (event.id != null && current.dmMessages.any { it.id == line.id }) current
            else current.copy(dmMessages = current.dmMessages + line)
        }
        if (!line.fromMe) {
            scope.launch { connection.send(DmMarkReadCommand(event.conversationId)) }
        }
    }

    private fun onHistory(event: HistoryEvent) {
        // A history reply for a channel we have since left is stale; drop it.
        val selected = _state.value.selectedChannelId
        if (event.channelId != null && event.channelId != selected) return

        val lines = event.messages
            .filter { it.messageType != "SYSTEM" }
            .map { it.toChatLine() }
            .sortedBy { it.timestamp }

        _state.update { current ->
            val existing = current.messages.associateBy { it.id }
            val merged = (lines.filterNot { existing.containsKey(it.id) } + current.messages)
                .sortedBy { it.timestamp }
            current.copy(messages = merged, loadingHistory = false)
        }
    }

    private fun onText(event: TextEvent) {
        if (event.channelId != _state.value.selectedChannelId) return
        val line = ChatLine(
            // Mumble and Discord relays carry no id at all, so synthesise a stable one
            // from the content rather than letting them collide on an empty string.
            id = event.id ?: "relay-${event.timestamp}-${event.username}-${event.text.hashCode()}",
            username = event.username,
            text = event.text,
            timestamp = event.timestamp,
            origin = event.origin,
            replyToId = event.replyToId,
        )
        _state.update { current ->
            if (current.messages.any { it.id == line.id }) current
            else current.copy(messages = current.messages + line)
        }
    }

    /**
     * Tracks who is typing.
     *
     * A local expiry runs alongside the server's own: its `typing:false` is emitted
     * from a timer holding a possibly stale socket reference, so relying on that alone
     * can leave an indicator stuck on forever.
     */
    /**
     * Folds a live image into the message list.
     *
     * The caption is the message body, so an image with no caption renders as an
     * attachment on its own rather than as an empty line with a picture beneath it.
     */
    private fun onImage(event: ImageEvent) {
        if (event.channelId != _state.value.selectedChannelId) return
        val line = ChatLine(
            id = event.id ?: ("img-" + System.nanoTime()),
            username = event.username,
            text = event.caption,
            timestamp = event.timestamp,
            origin = MessageOrigin.WEB,
            attachment = event.toAttachment(),
        )
        _state.update { current ->
            if (event.id != null && current.messages.any { it.id == line.id }) current
            else current.copy(messages = current.messages + line)
        }
    }

    private fun onTyping(event: TypingEvent) {
        if (event.channelId != _state.value.selectedChannelId) return
        _state.update { current ->
            current.copy(
                typing = if (event.typing) current.typing + event.username
                else current.typing - event.username,
            )
        }
        if (event.typing) {
            typingExpiry[event.username]?.cancel()
            typingExpiry[event.username] = scope.launch {
                delay(TYPING_EXPIRE_MS)
                _state.update { it.copy(typing = it.typing - event.username) }
            }
        }
    }

    /** Later entries win, so a `channel_update` refreshes rather than duplicates. */
    private fun merge(
        existing: List<BridgeChannel>,
        incoming: List<BridgeChannel>,
    ): List<BridgeChannel> {
        if (incoming.isEmpty()) return existing
        val byId = existing.associateBy { it.id }.toMutableMap()
        incoming.forEach { byId[it.id] = it }
        return byId.values.sortedWith(compareBy({ it.parentId }, { it.name }))
    }
}

/** Optimistic add/remove of one user's reaction, mirroring what the server will do. */
internal fun applyLocalReaction(
    existing: List<Reaction>,
    emoji: String,
    username: String,
    add: Boolean,
): List<Reaction> {
    val current = existing.firstOrNull { it.emoji == emoji }
    return when {
        add && current == null -> existing + Reaction(emoji, 1, listOf(username))
        add -> existing.map {
            if (it.emoji == emoji && username !in it.users) {
                it.copy(count = it.count + 1, users = it.users + username)
            } else {
                it
            }
        }
        current == null -> existing
        // Dropping the last reactor removes the pill entirely, as the server does.
        current.users.size <= 1 && username in current.users -> existing.filterNot { it.emoji == emoji }
        else -> existing.map {
            if (it.emoji == emoji) {
                it.copy(count = (it.count - 1).coerceAtLeast(0), users = it.users - username)
            } else {
                it
            }
        }
    }
}

private const val TAG = "BridgeRepository"
private const val TYPING_THROTTLE_MS = 3_000L
private const val TYPING_EXPIRE_MS = 8_000L

private fun BridgeMessage.toChatLine() = ChatLine(
    id = id,
    username = username,
    text = content,
    timestamp = createdAt,
    origin = MessageOrigin.WEB,
    isPinned = isPinned,
    attachment = attachment,
    replyToId = replyToId,
)

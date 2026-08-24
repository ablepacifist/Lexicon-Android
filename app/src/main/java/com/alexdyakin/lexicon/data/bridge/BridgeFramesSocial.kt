package com.alexdyakin.lexicon.data.bridge

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

// ── presence: two disjoint namespaces ────────────────────────────────────────

/**
 * A web/mobile client, keyed by `"web_<userId>"`.
 *
 * Deliberately NOT merged with [MumbleUser]. That one is keyed by Mumble session id and
 * is what audio frames reference; this one is what `voice_state` and the member list
 * reference. They identify different things and the server never relates them, so
 * unifying would invent a relationship that does not exist.
 */
@Serializable
data class WebClient(
    val id: String = "",
    val username: String = "",
    val channelId: Int = 0,
    val inVoice: Boolean = false,
    val voiceChannelId: Int? = null,
    val avatarUrl: String? = null,
)

@Serializable
data class WebUsersEvent(val webClients: List<WebClient> = emptyList()) : BridgeEvent {
    override val type: String get() = "web_users"
}

@Serializable
data class WebUserJoinEvent(val webClient: WebClient = WebClient()) : BridgeEvent {
    override val type: String get() = "web_user_join"
}

@Serializable
data class WebUserLeaveEvent(val id: String = "", val username: String = "") : BridgeEvent {
    override val type: String get() = "web_user_leave"
}

@Serializable
data class VoiceStateEvent(
    val id: String = "",
    val username: String = "",
    val inVoice: Boolean = false,
    val voiceChannelId: Int? = null,
) : BridgeEvent {
    override val type: String get() = "voice_state"
}

@Serializable
data class AvatarUpdatedEvent(val username: String = "", val avatarUrl: String = "") : BridgeEvent {
    override val type: String get() = "avatar_updated"
}

// ── typing ───────────────────────────────────────────────────────────────────

/**
 * The server throttles these to one per three seconds per user+channel and emits a
 * `typing:false` of its own after eight seconds of silence. The sender is always
 * excluded, so you never see your own.
 */
@Serializable
data class TypingEvent(
    val username: String = "",
    val userId: Int = 0,
    val channelId: Int = 0,
    val typing: Boolean = false,
) : BridgeEvent {
    override val type: String get() = "typing"
}

// ── reactions ────────────────────────────────────────────────────────────────

@Serializable
data class Reaction(
    val emoji: String = "",
    val count: Int = 0,
    val users: List<String> = emptyList(),
)

/**
 * Note [channelId] is absent on the reply to `get_reactions` but present on the
 * broadcast, hence nullable.
 */
@Serializable
data class ReactionUpdateEvent(
    @Serializable(with = LenientIdSerializer::class)
    val messageId: String = "",
    val channelId: Int? = null,
    val reactions: List<Reaction> = emptyList(),
) : BridgeEvent {
    override val type: String get() = "reaction_update"
}

// ── mentions and notifications ───────────────────────────────────────────────

/**
 * One row of `get_notifications`.
 *
 * [isRead] is an Int, not a Boolean: the column is a MySQL `TINYINT(1)` and mysql2
 * hands back 0/1, which lands in the JSON as a number.
 */
@Serializable
data class BridgeNotification(
    val id: Long = 0,
    val notifType: String = "",
    val fromUsername: String? = null,
    val channelId: Int? = null,
    val channelName: String? = null,
    val messageId: Long? = null,
    val messagePreview: String? = null,
    val isRead: Int = 0,
    val createdAt: String = "",
) {
    val read: Boolean get() = isRead != 0
}

@Serializable
data class NotificationsEvent(
    val notifications: List<BridgeNotification> = emptyList(),
    val unreadCount: Int = 0,
) : BridgeEvent {
    override val type: String get() = "notifications"
}

/**
 * The live push when someone mentions you.
 *
 * Its shape differs from a [BridgeNotification]: it is nested under `notification` and
 * carries no `messageId` or `isRead`, so it gets its own type rather than being forced
 * into the list model.
 */
@Serializable
data class LiveNotification(
    val id: Long = 0,
    val notifType: String = "",
    val fromUsername: String = "",
    val channelId: Int? = null,
    val channelName: String? = null,
    val messagePreview: String = "",
    val createdAt: String = "",
)

@Serializable
data class NotificationPushEvent(val notification: LiveNotification = LiveNotification()) : BridgeEvent {
    override val type: String get() = "notification"
}

// ── direct messages ──────────────────────────────────────────────────────────

@Serializable
data class DmConversation(
    val id: Long = 0,
    val otherUsername: String = "",
    val otherUserId: Int = 0,
    val lastMessageAt: String? = null,
    val createdAt: String = "",
)

@Serializable
data class DmConversationsEvent(
    val conversations: List<DmConversation> = emptyList(),
) : BridgeEvent {
    override val type: String get() = "dm_conversations_list"
}

/** [id] can be null even on a successful send, when the Lexicon store call failed. */
@Serializable
data class DmMessageEvent(
    val conversationId: Long = 0,
    @Serializable(with = LenientIdSerializer::class)
    val id: String? = null,
    val fromUserId: Int = 0,
    val fromUsername: String = "",
    val toUsername: String = "",
    val content: String = "",
    val timestamp: String = "",
) : BridgeEvent {
    override val type: String get() = "dm_message"
}

@Serializable
data class DmHistoryRow(
    @Serializable(with = LenientIdSerializer::class)
    val id: String = "",
    val fromUsername: String = "",
    val fromUserId: Int = 0,
    val content: String = "",
    val timestamp: String = "",
    val messageType: String = "TEXT",
)

@Serializable
data class DmHistoryEvent(
    val conversationId: Long = 0,
    val messages: List<DmHistoryRow> = emptyList(),
) : BridgeEvent {
    override val type: String get() = "dm_history"
}

@Serializable
data class DmOpenedEvent(
    val conversationId: Long = 0,
    val otherUsername: String = "",
    val otherUserId: Int = 0,
) : BridgeEvent {
    override val type: String get() = "dm_opened"
}

@Serializable
data class DmUnread(val conversationId: Long = 0, val unreadCount: Int = 0)

@Serializable
data class DmUnreadCountsEvent(val unreads: List<DmUnread> = emptyList()) : BridgeEvent {
    override val type: String get() = "dm_unread_counts"
}

@Serializable
data class DmUnreadUpdateEvent(
    val conversationId: Long = 0,
    val fromUsername: String = "",
    val unreadCount: Int = 0,
) : BridgeEvent {
    override val type: String get() = "dm_unread_update"
}

// ── pinned messages (read only) ──────────────────────────────────────────────

@Serializable
data class PinnedMessage(
    val id: Long = 0,
    val channelId: Int = 0,
    @Serializable(with = LenientIdSerializer::class)
    val messageId: String = "",
    val username: String = "",
    val content: String = "",
    val messageType: String = "TEXT",
    val pinnedBy: String = "",
    val pinnedAt: String = "",
)

@Serializable
data class PinnedMessagesEvent(
    val channelId: Int = 0,
    val pins: List<PinnedMessage> = emptyList(),
) : BridgeEvent {
    override val type: String get() = "pinned_messages"
}

@Serializable
data class PinAddedEvent(val channelId: Int = 0, val pin: PinnedMessage = PinnedMessage()) : BridgeEvent {
    override val type: String get() = "pin_added"
}

@Serializable
data class PinRemovedEvent(
    val channelId: Int = 0,
    @Serializable(with = LenientIdSerializer::class)
    val messageId: String = "",
) : BridgeEvent {
    override val type: String get() = "pin_removed"
}

// ── outbound ─────────────────────────────────────────────────────────────────

/** Self-throttle to ~3s; the server ignores anything faster anyway. */
data class TypingStartCommand(val channelId: Int) : BridgeCommand {
    override val type: String get() = "typing_start"
    override fun toJson() = kotlinx.serialization.json.buildJsonObject {
        put("type", type); put("channelId", channelId)
    }
}

data class TypingStopCommand(val channelId: Int) : BridgeCommand {
    override val type: String get() = "typing_stop"
    override fun toJson() = kotlinx.serialization.json.buildJsonObject {
        put("type", type); put("channelId", channelId)
    }
}

/**
 * `channelId` is always sent: the server defaults it to 0, which would store the
 * reaction against the root channel and broadcast it somewhere nobody is looking.
 */
data class ReactionAddCommand(
    val messageId: String,
    val emoji: String,
    val channelId: Int,
) : BridgeCommand {
    override val type: String get() = "reaction_add"
    override fun toJson() = kotlinx.serialization.json.buildJsonObject {
        put("type", type); put("messageId", messageId); put("emoji", emoji); put("channelId", channelId)
    }
}

data class ReactionRemoveCommand(
    val messageId: String,
    val emoji: String,
    val channelId: Int,
) : BridgeCommand {
    override val type: String get() = "reaction_remove"
    override fun toJson() = kotlinx.serialization.json.buildJsonObject {
        put("type", type); put("messageId", messageId); put("emoji", emoji); put("channelId", channelId)
    }
}

/**
 * Capped at 100 ids by the server. Messages with no reactions produce **no frame at
 * all**, so absence means empty and this cannot be used to detect completion.
 */
data class GetReactionsBatchCommand(val messageIds: List<String>) : BridgeCommand {
    override val type: String get() = "get_reactions_batch"
    override fun toJson() = kotlinx.serialization.json.buildJsonObject {
        put("type", type)
        put(
            "messageIds",
            kotlinx.serialization.json.buildJsonArray {
                messageIds.take(100).forEach { add(it) }
            },
        )
    }
}

data class GetPinnedMessagesCommand(val channelId: Int) : BridgeCommand {
    override val type: String get() = "get_pinned_messages"
    override fun toJson() = kotlinx.serialization.json.buildJsonObject {
        put("type", type); put("channelId", channelId)
    }
}

data class GetNotificationsCommand(val limit: Int = 50) : BridgeCommand {
    override val type: String get() = "get_notifications"
    override fun toJson() = kotlinx.serialization.json.buildJsonObject {
        put("type", type); put("limit", limit)
    }
}

/** Omitting the id marks everything read. */
data class NotificationsReadCommand(val notificationId: Long? = null) : BridgeCommand {
    override val type: String get() = "notifications_read"
    override fun toJson() = kotlinx.serialization.json.buildJsonObject {
        put("type", type)
        notificationId?.let { put("notificationId", it) }
    }
}

data class DmConversationsCommand(private val unused: Unit = Unit) : BridgeCommand {
    override val type: String get() = "dm_conversations"
    override fun toJson() = kotlinx.serialization.json.buildJsonObject { put("type", type) }
}

/** Note the key is `username` here but `toUsername` on send — the server is inconsistent. */
data class DmOpenCommand(val username: String) : BridgeCommand {
    override val type: String get() = "dm_open"
    override fun toJson() = kotlinx.serialization.json.buildJsonObject {
        put("type", type); put("username", username)
    }
}

data class DmSendCommand(val toUsername: String, val content: String) : BridgeCommand {
    override val type: String get() = "dm_send"
    override fun toJson() = kotlinx.serialization.json.buildJsonObject {
        put("type", type); put("toUsername", toUsername); put("content", content.take(4000))
    }
}

data class DmHistoryCommand(val conversationId: Long, val limit: Int = 50) : BridgeCommand {
    override val type: String get() = "dm_history"
    override fun toJson() = kotlinx.serialization.json.buildJsonObject {
        put("type", type); put("conversationId", conversationId); put("limit", limit)
    }
}

data class DmMarkReadCommand(val conversationId: Long) : BridgeCommand {
    override val type: String get() = "dm_mark_read"
    override fun toJson() = kotlinx.serialization.json.buildJsonObject {
        put("type", type); put("conversationId", conversationId)
    }
}

// ── voice ────────────────────────────────────────────────────────────────────

/**
 * The server has opened its own Mumble connection and audio may now flow.
 *
 * Binary frames sent before this are discarded without a word, so the session must
 * wait for it. It can take a while — the bridge dials a fresh TLS connection to the
 * Mumble server, with retries — so a slow reply is not a failure.
 */
@Serializable
class VoiceReadyEvent : BridgeEvent {
    override val type: String get() = "voice_ready"

    override fun equals(other: Any?) = other is VoiceReadyEvent
    override fun hashCode() = type.hashCode()
}

@Serializable
class VoiceStoppedEvent : BridgeEvent {
    override val type: String get() = "voice_stopped"

    override fun equals(other: Any?) = other is VoiceStoppedEvent
    override fun hashCode() = type.hashCode()
}

/**
 * Opens the voice session.
 *
 * Note the key: this one is [voiceChannelId] while [VoiceJoinChannelCommand] calls the
 * same concept `channelId`. The server also treats `0` as "no channel", so a real
 * channel is only sent when there is one.
 */
data class VoiceStartCommand(val voiceChannelId: Int? = null) : BridgeCommand {
    override val type: String get() = "voice_start"
    override fun toJson() = kotlinx.serialization.json.buildJsonObject {
        put("type", type)
        voiceChannelId?.takeIf { it != 0 }?.let { put("voiceChannelId", it) }
    }
}

/** Moves an already-open session. Uses `channelId`, unlike [VoiceStartCommand]. */
data class VoiceJoinChannelCommand(val channelId: Int) : BridgeCommand {
    override val type: String get() = "voice_join_channel"
    override fun toJson() = kotlinx.serialization.json.buildJsonObject {
        put("type", type); put("channelId", channelId)
    }
}

data class VoiceStopCommand(private val unused: Unit = Unit) : BridgeCommand {
    override val type: String get() = "voice_stop"
    override fun toJson() = kotlinx.serialization.json.buildJsonObject { put("type", type) }
}

// ── images ───────────────────────────────────────────────────────────────────

/**
 * An image on a message.
 *
 * Every URL here is relative to the bridge host rather than to Lexicon, and the
 * thumbnail is the one to display: [fileUrl] is the full-size original, which on a
 * phone is wasted bytes until someone actually opens it.
 */
@Serializable
data class ChatAttachment(
    val fileId: Long? = null,
    val url: String? = null,
    val fileUrl: String? = null,
    val thumbnailUrl: String? = null,
    val originalFilename: String? = null,
    val mimeType: String? = null,
    val width: Int? = null,
    val height: Int? = null,
) {
    /** What to show inline, preferring the thumbnail as the web client does. */
    val previewUrl: String? get() = thumbnailUrl ?: fileUrl ?: url

    /** What to open full screen. */
    val fullUrl: String? get() = fileUrl ?: url ?: thumbnailUrl

    val isGif: Boolean get() = mimeType == "image/gif"
}

/**
 * A live image message.
 *
 * The fields sit flat on the frame rather than nested under `attachment`, unlike the
 * history row for the same message — so the two are converted to a common shape rather
 * than shared.
 */
@Serializable
data class ImageEvent(
    val channelId: Int = 0,
    @Serializable(with = LenientIdSerializer::class)
    val id: String? = null,
    val userId: Int = 0,
    val username: String = "",
    val fileId: Long? = null,
    val fileUrl: String? = null,
    val thumbnailUrl: String? = null,
    val originalFilename: String? = null,
    val mimeType: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val caption: String = "",
    val timestamp: String = "",
) : BridgeEvent {
    override val type: String get() = "image"

    fun toAttachment() = ChatAttachment(
        fileId = fileId,
        fileUrl = fileUrl,
        thumbnailUrl = thumbnailUrl,
        originalFilename = originalFilename,
        mimeType = mimeType,
        width = width,
        height = height,
    )
}

/** Posts an already-uploaded image. The upload itself is a REST call, not a frame. */
data class SendImageCommand(
    val channelId: Int,
    val fileId: Long,
    val fileUrl: String?,
    val thumbnailUrl: String?,
    val originalFilename: String?,
    val mimeType: String?,
    val width: Int?,
    val height: Int?,
    val caption: String,
) : BridgeCommand {
    override val type: String get() = "image"
    override fun toJson() = kotlinx.serialization.json.buildJsonObject {
        put("type", type)
        put("channelId", channelId)
        put("fileId", fileId)
        fileUrl?.let { put("fileUrl", it) }
        thumbnailUrl?.let { put("thumbnailUrl", it) }
        originalFilename?.let { put("originalFilename", it) }
        mimeType?.let { put("mimeType", it) }
        width?.let { put("width", it) }
        height?.let { put("height", it) }
        put("isGif", mimeType == "image/gif")
        put("caption", caption)
    }
}

package com.alexdyakin.lexicon.data.bridge

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Wire model for the Mumble bridge WebSocket.
 *
 * The bridge has no written protocol spec — its source is the specification. Two
 * properties of that wire format shape everything here:
 *
 *  1. The envelope is **flat**: `{"type": "...", ...fields}`. There is no nested
 *     `data`/`payload`, so kotlinx's `@JsonClassDiscriminator` (which expects the
 *     discriminator alongside a wrapped body) is the wrong tool.
 *     [BridgeEventSerializer] picks a concrete type by reading `type` instead.
 *  2. There are roughly forty message types and the set grows. Anything unmodelled
 *     MUST decode to [UnknownEvent] rather than throwing, or one new server message
 *     would take the whole socket down.
 */
@Serializable(with = BridgeEventSerializer::class)
sealed interface BridgeEvent {
    val type: String
}

// ── handshake ────────────────────────────────────────────────────────────────

/**
 * Sent unprompted the moment the socket opens, and **again** after authentication
 * with only the channels that just became visible (and `users: []`).
 *
 * Callers must MERGE the two. Replacing on the second frame drops every channel that
 * arrived in the first.
 */
@Serializable
data class ServerStateEvent(
    val channels: List<BridgeChannel> = emptyList(),
    val users: List<MumbleUser> = emptyList(),
) : BridgeEvent {
    override val type: String get() = "server_state"
}

@Serializable
data class AuthOkEvent(
    val username: String = "",
    val userId: Int? = null,
    val isAdmin: Boolean = false,
    val sso: Boolean = false,
    /** Opaque token for this sign-in. The bridge's HTTP routes (image upload) want it as a bearer token. */
    val sessionToken: String? = null,
) : BridgeEvent {
    override val type: String get() = "auth_ok"
}

/** Only ever produced by a bad or expired SSO token. */
@Serializable
data class AuthErrorEvent(val message: String = "") : BridgeEvent {
    override val type: String get() = "auth_error"
}

/**
 * The bridge's only general failure frame. It carries **no code and no correlation
 * id**, so it cannot be attributed to a particular request — surface it as a
 * transient banner, never as a rejected call.
 */
@Serializable
data class ErrorEvent(val message: String = "") : BridgeEvent {
    override val type: String get() = "error"
}

// ── channels ─────────────────────────────────────────────────────────────────

@Serializable
data class BridgeChannel(
    val id: Int = 0,
    val name: String = "",
    val parentId: Int = 0,
    val description: String = "",
    /**
     * Present and true only for restricted channels. The server *deletes* this key
     * when unrestricting rather than setting it false, so absent means unrestricted.
     */
    val restricted: Boolean = false,
    val discordChannelId: String? = null,
)

@Serializable
data class ChannelUpdateEvent(val channel: BridgeChannel = BridgeChannel()) : BridgeEvent {
    override val type: String get() = "channel_update"
}

@Serializable
data class ChannelRemoveEvent(val channelId: Int = 0) : BridgeEvent {
    override val type: String get() = "channel_remove"
}

@Serializable
data class JoinedChannelEvent(val channelId: Int = 0) : BridgeEvent {
    override val type: String get() = "joined_channel"
}

// ── presence ─────────────────────────────────────────────────────────────────

/**
 * A native Mumble user, keyed by [session].
 *
 * This is a **different identity space** from web clients, which are keyed by
 * `"web_<userId>"`. Audio frames carry a Mumble session id, so this map — not the
 * web presence list — is what maps a speaker to a name.
 */
@Serializable
data class MumbleUser(
    val session: Long = 0,
    val name: String = "",
    val channelId: Int = 0,
    val mute: Boolean = false,
    val deaf: Boolean = false,
    val selfMute: Boolean = false,
    val selfDeaf: Boolean = false,
)

@Serializable
data class UserUpdateEvent(val user: MumbleUser = MumbleUser()) : BridgeEvent {
    override val type: String get() = "user_update"
}

@Serializable
data class UserRemoveEvent(
    val session: Long = 0,
    /** Absent when the server had no record of the session. */
    val name: String? = null,
) : BridgeEvent {
    override val type: String get() = "user_remove"
}

// ── messages ─────────────────────────────────────────────────────────────────

/**
 * A chat message row.
 *
 * These come straight from Lexicon's `TextMessage` (the bridge only adds [isPinned]),
 * so the field names are Lexicon's, not the bridge's.
 *
 * [createdAt] is a Java `LocalDateTime` serialized without a timezone and with
 * microsecond precision (`2026-08-16T14:43:48.835365`). It is deliberately kept as a
 * String: `java.time` needs API 26, this module targets 24 without desugaring, and the
 * same string doubles as the `before` pagination cursor, so reformatting it would break
 * paging.
 */
@Serializable
data class BridgeMessage(
    @Serializable(with = LenientIdSerializer::class)
    val id: String = "",
    val channelId: Int = 0,
    val channelName: String = "",
    val userId: Int = 0,
    val username: String = "",
    val content: String = "",
    /** TEXT, IMAGE, GIF, MEDIA_SHARE, MIXED, SYSTEM or BOT_COMMAND. */
    val messageType: String = "TEXT",
    val mediaFileId: Long? = null,
    @Serializable(with = LenientIdSerializer::class)
    val replyToId: String? = null,
    val isPinned: Boolean = false,
    /** Present on IMAGE and GIF rows; the live frame carries the same data flat. */
    val attachment: ChatAttachment? = null,
    val createdAt: String = "",
    val editedAt: String? = null,
)

/**
 * Ids arrive as a JSON number on some paths and a string on others — the bridge
 * stringifies in `get_reactions_batch` and pins but echoes the raw client value
 * elsewhere. Normalising to String at the edge lets caches key on one type.
 */
object LenientIdSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LenientId", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String =
        (decoder as JsonDecoder).decodeJsonElement().jsonPrimitive.content

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}

/** Where a chat line came from. The bridge distinguishes these by a `source` field. */
enum class MessageOrigin { WEB, MUMBLE, DISCORD }

/**
 * A live chat line.
 *
 * Three shapes share this type. Web messages carry `userId`/`id`/`html` and have **no**
 * `source` field at all; native Mumble and Discord relays set `source` and omit the ids.
 */
@Serializable
data class TextEvent(
    val channelId: Int = 0,
    val username: String = "",
    val text: String = "",
    val userId: Int? = null,
    @Serializable(with = LenientIdSerializer::class)
    val id: String? = null,
    val html: String? = null,
    @Serializable(with = LenientIdSerializer::class)
    val replyToId: String? = null,
    val timestamp: String = "",
    /** Absent for web messages; "mumble" or "discord" otherwise. */
    val source: String? = null,
) : BridgeEvent {
    override val type: String get() = "text"

    val origin: MessageOrigin
        get() = when (source) {
            "mumble" -> MessageOrigin.MUMBLE
            "discord" -> MessageOrigin.DISCORD
            else -> MessageOrigin.WEB
        }
}

/**
 * Reply to `get_history`.
 *
 * The bridge echoes the raw `channelId` it was sent rather than the defaulted one, so
 * omitting it on the request yields a response with no `channelId` at all. Requests
 * always set it explicitly for that reason.
 */
@Serializable
data class HistoryEvent(
    val channelId: Int? = null,
    val messages: List<BridgeMessage> = emptyList(),
    @SerialName("_isRefresh")
    val isRefresh: Boolean = false,
) : BridgeEvent {
    override val type: String get() = "history"
}

// ── anything not modelled yet ────────────────────────────────────────────────

/**
 * Catch-all so an unmodelled or newly added server message is inert rather than
 * fatal. [raw] is kept so a later stage can add a real type without changing the
 * transport.
 */
data class UnknownEvent(override val type: String, val raw: JsonObject) : BridgeEvent

/**
 * Decodes any frame we do not model into [UnknownEvent], preserving the raw object.
 * Encoding is unsupported — nothing in the app constructs one.
 */
object UnknownEventSerializer : KSerializer<UnknownEvent> {
    override val descriptor = JsonObject.serializer().descriptor

    override fun deserialize(decoder: Decoder): UnknownEvent {
        val obj = (decoder as JsonDecoder).decodeJsonElement().jsonObject
        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        return UnknownEvent(type, obj)
    }

    override fun serialize(encoder: Encoder, value: UnknownEvent) =
        error("UnknownEvent is inbound only")
}

/** Selects a concrete event by the flat `type` discriminator. */
object BridgeEventSerializer : JsonContentPolymorphicSerializer<BridgeEvent>(BridgeEvent::class) {
    override fun selectDeserializer(element: JsonElement): KSerializer<out BridgeEvent> =
        when (element.jsonObject["type"]?.jsonPrimitive?.contentOrNull) {
            "server_state" -> ServerStateEvent.serializer()
            "auth_ok" -> AuthOkEvent.serializer()
            "auth_error" -> AuthErrorEvent.serializer()
            "error" -> ErrorEvent.serializer()
            "channel_update" -> ChannelUpdateEvent.serializer()
            "channel_remove" -> ChannelRemoveEvent.serializer()
            "joined_channel" -> JoinedChannelEvent.serializer()
            "user_update" -> UserUpdateEvent.serializer()
            "user_remove" -> UserRemoveEvent.serializer()
            "text" -> TextEvent.serializer()
            "history" -> HistoryEvent.serializer()
            "web_users" -> WebUsersEvent.serializer()
            "web_user_join" -> WebUserJoinEvent.serializer()
            "web_user_leave" -> WebUserLeaveEvent.serializer()
            "voice_state" -> VoiceStateEvent.serializer()
            "image" -> ImageEvent.serializer()
            "voice_ready" -> VoiceReadyEvent.serializer()
            "voice_stopped" -> VoiceStoppedEvent.serializer()
            "avatar_updated" -> AvatarUpdatedEvent.serializer()
            "typing" -> TypingEvent.serializer()
            "reaction_update" -> ReactionUpdateEvent.serializer()
            "notifications" -> NotificationsEvent.serializer()
            "notification" -> NotificationPushEvent.serializer()
            "dm_conversations_list" -> DmConversationsEvent.serializer()
            "dm_message" -> DmMessageEvent.serializer()
            "dm_history" -> DmHistoryEvent.serializer()
            "dm_opened" -> DmOpenedEvent.serializer()
            "dm_unread_counts" -> DmUnreadCountsEvent.serializer()
            "dm_unread_update" -> DmUnreadUpdateEvent.serializer()
            "pinned_messages" -> PinnedMessagesEvent.serializer()
            "pin_added" -> PinAddedEvent.serializer()
            "pin_removed" -> PinRemovedEvent.serializer()
            else -> UnknownEventSerializer
        }
}

// ── outbound ─────────────────────────────────────────────────────────────────

/**
 * Something the client sends.
 *
 * Each command builds its own [JsonObject] rather than relying on reflective
 * encoding. A computed `type` property has no backing field and would simply not be
 * emitted, and a defaulted constructor property would depend on the DI `Json` keeping
 * `encodeDefaults = true` — a command silently losing its `type` is not a failure mode
 * worth risking for brevity.
 */
sealed interface BridgeCommand {
    val type: String
    fun toJson(): JsonObject
}

data class SsoAuthCommand(val token: String) : BridgeCommand {
    override val type: String get() = "sso_auth"
    override fun toJson() = buildJsonObject {
        put("type", type)
        put("token", token)
    }
}

data class JoinChannelCommand(val channelId: Int) : BridgeCommand {
    override val type: String get() = "join_channel"
    override fun toJson() = buildJsonObject {
        put("type", type)
        put("channelId", channelId)
    }
}

/**
 * `channelId` is always written, even though the server would default it: the handler
 * echoes the raw value back, so omitting it produces a response with no channel at all.
 */
data class GetHistoryCommand(
    val channelId: Int,
    val limit: Int = 50,
    /** The `createdAt` of the oldest message held, for paging further back. */
    val before: String? = null,
) : BridgeCommand {
    override val type: String get() = "get_history"
    override fun toJson() = buildJsonObject {
        put("type", type)
        put("channelId", channelId)
        put("limit", limit)
        before?.let { put("before", it) }
    }
}

data class SendTextCommand(
    val channelId: Int,
    val text: String,
    val replyToMessageId: String? = null,
) : BridgeCommand {
    override val type: String get() = "text"
    override fun toJson() = buildJsonObject {
        put("type", type)
        put("channelId", channelId)
        put("text", text)
        replyToMessageId?.let { put("replyToMessageId", it) }
    }
}

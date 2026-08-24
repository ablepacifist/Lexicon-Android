package com.alexdyakin.lexicon.data.bridge

/**
 * A row in the rendered channel sidebar.
 *
 * The bridge sends a flat list with `parentId`, but Mumble channels are a tree and the
 * web client renders them as one. Getting this wrong is not cosmetic: `Servers`, `D&D`,
 * `communications` and `Voice Channels` are **folders**, not places you can post, and
 * `Root` is not a channel at all.
 */
sealed interface ChannelRow {
    /** A folder: a top-level channel that has children. Not selectable. */
    data class Category(val id: Int, val name: String, val collapsed: Boolean) : ChannelRow

    /** Somewhere you can actually read and post. */
    data class Channel(
        val channel: BridgeChannel,
        val isVoice: Boolean,
        val indented: Boolean,
    ) : ChannelRow
}

/**
 * Builds the sidebar exactly the way `renderChannels()` in the web client does
 * (`mumble-bridge/public/js/chat.js:669`):
 *
 *  - **Root (id 0) is skipped entirely** — it is the Mumble tree root, not a channel.
 *  - A top-level channel **with children is a category**; it gets a header and cannot
 *    be selected.
 *  - A top-level channel **without children** is a normal channel.
 *  - Children are listed under their category, indented.
 *  - Ordering is by id within each group, matching the web client rather than
 *    alphabetical, so the two look the same.
 */
fun buildChannelRows(
    channels: List<BridgeChannel>,
    collapsed: Set<Int> = emptySet(),
): List<ChannelRow> {
    val byParent = channels
        .filter { it.id != ROOT_CHANNEL_ID }
        .groupBy { it.parentId }

    val topLevel = byParent[ROOT_CHANNEL_ID].orEmpty().sortedBy { it.id }
    val byId = channels.associateBy { it.id }

    val rows = mutableListOf<ChannelRow>()

    // Standalone channels first, then categories — the web client's order.
    topLevel.filter { byParent[it.id].isNullOrEmpty() }.forEach { channel ->
        rows += ChannelRow.Channel(channel, isVoiceChannel(channel, byId), indented = false)
    }

    topLevel.filter { !byParent[it.id].isNullOrEmpty() }.forEach { category ->
        val isCollapsed = category.id in collapsed
        rows += ChannelRow.Category(category.id, category.name, isCollapsed)
        if (!isCollapsed) {
            byParent[category.id].orEmpty().sortedBy { it.id }.forEach { child ->
                rows += ChannelRow.Channel(child, isVoiceChannel(child, byId), indented = true)
            }
        }
    }
    return rows
}

/**
 * Mirrors `createChannelItem`: a channel counts as voice if its parent category is
 * named something like "Voice Channels", or its own name hints at it. Mumble has no
 * flag for this — the web client infers it from names, so this must too or the two
 * disagree about which rooms show a speaker.
 */
internal fun isVoiceChannel(channel: BridgeChannel, byId: Map<Int, BridgeChannel>): Boolean {
    val parentName = byId[channel.parentId]?.name?.lowercase().orEmpty()
    if ("voice" in parentName) return true
    val own = channel.name.lowercase()
    return "voice" in own || "walky" in own || own == "afk"
}

const val ROOT_CHANNEL_ID = 0

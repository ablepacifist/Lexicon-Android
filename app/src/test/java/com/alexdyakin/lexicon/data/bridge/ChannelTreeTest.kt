package com.alexdyakin.lexicon.data.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sidebar previously rendered the flat channel list verbatim, which showed `Root`,
 * `Servers`, `D&D`, `communications` and `Voice Channels` as if they were places you
 * could post. They are the tree root and folders. These tests pin the real structure,
 * read from the live bridge.
 */
class ChannelTreeTest {

    /** The actual tree on voice.alex-dyakin.com. */
    private val realChannels = listOf(
        BridgeChannel(id = 0, name = "Root", parentId = 0),
        BridgeChannel(id = 1, name = "general", parentId = 0),
        BridgeChannel(id = 2, name = "Servers", parentId = 0),
        BridgeChannel(id = 3, name = "D&D", parentId = 0),
        BridgeChannel(id = 4, name = "communications", parentId = 0),
        BridgeChannel(id = 5, name = "Voice Channels", parentId = 0),
        BridgeChannel(id = 6, name = "minecraft", parentId = 2),
        BridgeChannel(id = 7, name = "valheim", parentId = 2),
        BridgeChannel(id = 9, name = "session-planning", parentId = 3),
        BridgeChannel(id = 10, name = "announcements", parentId = 4),
        BridgeChannel(id = 11, name = "memes", parentId = 4),
        BridgeChannel(id = 12, name = "General Voice", parentId = 5),
        BridgeChannel(id = 13, name = "Gaming Voice", parentId = 5),
        BridgeChannel(id = 14, name = "D&D Session", parentId = 5),
        BridgeChannel(id = 15, name = "AFK", parentId = 5),
        BridgeChannel(id = 16, name = "ark", parentId = 2),
        BridgeChannel(id = 17, name = "Ksp", parentId = 2),
    )

    private fun rows(collapsed: Set<Int> = emptySet()) = buildChannelRows(realChannels, collapsed)

    @Test
    fun `Root is never rendered`() {
        val names = rows().mapNotNull {
            when (it) {
                is ChannelRow.Category -> it.name
                is ChannelRow.Channel -> it.channel.name
            }
        }
        assertFalse("Root is the tree root, not a channel", names.contains("Root"))
    }

    @Test
    fun `folders are categories, not selectable channels`() {
        val categories = rows().filterIsInstance<ChannelRow.Category>().map { it.name }
        assertEquals(listOf("Servers", "D&D", "communications", "Voice Channels"), categories)

        // And none of them appear as somewhere you could post.
        val channelNames = rows().filterIsInstance<ChannelRow.Channel>().map { it.channel.name }
        listOf("Servers", "D&D", "communications", "Voice Channels").forEach {
            assertFalse("$it is a folder", channelNames.contains(it))
        }
    }

    @Test
    fun `general is the only standalone channel and comes first`() {
        val first = rows().first()
        assertTrue(first is ChannelRow.Channel)
        assertEquals("general", (first as ChannelRow.Channel).channel.name)
        assertFalse(first.indented)
    }

    @Test
    fun `children sit under their category, indented, ordered by id`() {
        val all = rows()
        val serversAt = all.indexOfFirst { it is ChannelRow.Category && it.name == "Servers" }
        val children = all.drop(serversAt + 1)
            .takeWhile { it is ChannelRow.Channel }
            .filterIsInstance<ChannelRow.Channel>()
        assertEquals(
            listOf("minecraft", "valheim", "ark", "Ksp"),
            children.map { it.channel.name },
        )
        assertTrue("children must be indented", children.all { it.indented })
    }

    @Test
    fun `collapsing a category hides its children but keeps the header`() {
        val open = rows()
        val shut = rows(collapsed = setOf(2))

        assertTrue(shut.any { it is ChannelRow.Category && it.name == "Servers" && it.collapsed })
        val names = shut.filterIsInstance<ChannelRow.Channel>().map { it.channel.name }
        assertFalse(names.contains("minecraft"))
        // Other categories are untouched.
        assertTrue(names.contains("memes"))
        assertEquals(open.size - 4, shut.size)
    }

    /**
     * Mumble has no voice flag; the web client infers it from names, so this must agree
     * or the two clients disagree about which rooms show a speaker.
     */
    @Test
    fun `voice rooms are inferred the way the web client infers them`() {
        val voice = rows().filterIsInstance<ChannelRow.Channel>()
            .filter { it.isVoice }
            .map { it.channel.name }
        assertEquals(listOf("General Voice", "Gaming Voice", "D&D Session", "AFK"), voice)

        // Text channels under a non-voice category stay text.
        val text = rows().filterIsInstance<ChannelRow.Channel>()
            .filterNot { it.isVoice }
            .map { it.channel.name }
        assertTrue(text.contains("minecraft"))
        assertTrue(text.contains("announcements"))
    }

    @Test
    fun `an empty channel list produces no rows`() {
        assertTrue(buildChannelRows(emptyList()).isEmpty())
        // Root alone is still nothing.
        assertTrue(buildChannelRows(listOf(BridgeChannel(id = 0, name = "Root"))).isEmpty())
    }
}

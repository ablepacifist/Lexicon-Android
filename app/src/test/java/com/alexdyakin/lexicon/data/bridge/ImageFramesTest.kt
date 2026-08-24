package com.alexdyakin.lexicon.data.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Image messages.
 *
 * The same picture reaches the client in two different shapes — flat on the live frame,
 * nested under `attachment` on a history row — so both are pinned here, along with the
 * URL precedence the web client uses. Getting that precedence wrong does not fail; it
 * just quietly downloads the full-size original on a phone every time.
 */
class ImageFramesTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun decode(raw: String) = json.decodeFromString(BridgeEventSerializer, raw)

    @Test
    fun `a live image frame carries its fields flat`() {
        val event = decode(
            """
            {"type":"image","channelId":28,"id":72,"userId":1,"username":"zx",
             "fileId":9,"fileUrl":"/api/chat/files/9","thumbnailUrl":"/api/chat/files/9/thumb",
             "originalFilename":"cat.png","mimeType":"image/png","width":800,"height":600,
             "caption":"look","timestamp":"2026-08-24T07:20:00.000Z"}
            """.trimIndent(),
        )
        assertTrue(event is ImageEvent)
        event as ImageEvent
        assertEquals("72", event.id)
        assertEquals("look", event.caption)
        assertEquals(800, event.width)

        val attachment = event.toAttachment()
        assertEquals("/api/chat/files/9/thumb", attachment.previewUrl)
        assertEquals("/api/chat/files/9", attachment.fullUrl)
    }

    /** The store call can fail after the broadcast succeeds, leaving no row id. */
    @Test
    fun `an image can arrive with a null id`() {
        val event = decode(
            """{"type":"image","channelId":28,"username":"zx","fileId":9,"caption":""}""",
        ) as ImageEvent
        assertNull(event.id)
    }

    /**
     * The thumbnail wins. Falling through to the original would pull megabytes into a
     * chat list without anything looking wrong.
     */
    @Test
    fun `the thumbnail is preferred for inline display`() {
        val both = ChatAttachment(fileUrl = "/full.png", thumbnailUrl = "/thumb.png")
        assertEquals("/thumb.png", both.previewUrl)
        assertEquals("/full.png", both.fullUrl)
    }

    @Test
    fun `without a thumbnail the original is used for both`() {
        val only = ChatAttachment(fileUrl = "/full.png")
        assertEquals("/full.png", only.previewUrl)
        assertEquals("/full.png", only.fullUrl)
    }

    /** Older rows use `url` rather than `fileUrl`; both must resolve. */
    @Test
    fun `a bare url field still resolves`() {
        val legacy = ChatAttachment(url = "/legacy.png")
        assertEquals("/legacy.png", legacy.previewUrl)
        assertEquals("/legacy.png", legacy.fullUrl)
    }

    @Test
    fun `an attachment with no urls at all resolves to nothing`() {
        assertNull(ChatAttachment(originalFilename = "gone.png").previewUrl)
    }

    @Test
    fun `gifs are recognised by mime type`() {
        assertTrue(ChatAttachment(mimeType = "image/gif").isGif)
        assertFalse(ChatAttachment(mimeType = "image/png").isGif)
        assertFalse(ChatAttachment().isGif)
    }

    /** History nests the same data one level down, unlike the live frame. */
    @Test
    fun `a history row keeps its nested attachment`() {
        val event = decode(
            """
            {"type":"history","channelId":28,"messages":[
              {"id":72,"channelId":28,"userId":1,"username":"zx","content":"look",
               "messageType":"IMAGE","isPinned":false,"createdAt":"2026-08-24T07:20:00",
               "attachment":{"fileId":9,"fileUrl":"/api/chat/files/9",
                             "thumbnailUrl":"/api/chat/files/9/thumb","mimeType":"image/png",
                             "width":800,"height":600,"originalFilename":"cat.png"}}
            ]}
            """.trimIndent(),
        ) as HistoryEvent

        val row = event.messages.single()
        assertEquals("IMAGE", row.messageType)
        assertEquals("/api/chat/files/9/thumb", row.attachment?.previewUrl)
        assertEquals(600, row.attachment?.height)
    }

    /** A plain text row has no attachment, and must not invent an empty one. */
    @Test
    fun `a text row has no attachment`() {
        val event = decode(
            """
            {"type":"history","channelId":28,"messages":[
              {"id":1,"username":"zx","content":"hi","messageType":"TEXT","createdAt":"x"}
            ]}
            """.trimIndent(),
        ) as HistoryEvent
        assertNull(event.messages.single().attachment)
    }

    // ── outbound ─────────────────────────────────────────────────────────────

    @Test
    fun `the outbound image command matches what the server reads`() {
        val sent = SendImageCommand(
            channelId = 28,
            fileId = 9,
            fileUrl = "/api/chat/files/9",
            thumbnailUrl = "/api/chat/files/9/thumb",
            originalFilename = "cat.gif",
            mimeType = "image/gif",
            width = 100,
            height = 50,
            caption = "hello",
        ).toJson()

        assertEquals("image", sent["type"]?.jsonPrimitive?.content)
        assertEquals("28", sent["channelId"]?.jsonPrimitive?.content)
        assertEquals("9", sent["fileId"]?.jsonPrimitive?.content)
        assertEquals("hello", sent["caption"]?.jsonPrimitive?.content)
        // The server branches on this to store the row as GIF rather than IMAGE.
        assertEquals("true", sent["isGif"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a non-gif is not flagged as one`() {
        val sent = SendImageCommand(
            channelId = 1, fileId = 2, fileUrl = null, thumbnailUrl = null,
            originalFilename = null, mimeType = "image/png",
            width = null, height = null, caption = "",
        ).toJson()
        assertEquals("false", sent["isGif"]?.jsonPrimitive?.content)
        // Absent optional fields are omitted rather than sent as null.
        assertFalse(sent.containsKey("fileUrl"))
        assertFalse(sent.containsKey("width"))
    }

    @Test
    fun `the upload result decodes the fields the command needs`() {
        val result = json.decodeFromString(
            ChatUploadResult.serializer(),
            """{"id":9,"url":"/api/chat/files/9","thumbnailUrl":"/api/chat/files/9/thumb",
                "originalFilename":"cat.png","mimeType":"image/png","width":800,"height":600}""",
        )
        assertEquals(9L, result.id)
        assertEquals("/api/chat/files/9", result.url)
        assertEquals(800, result.width)
    }

    /** Lexicon may store a file without producing dimensions; that must not fail. */
    @Test
    fun `an upload result without dimensions still decodes`() {
        val result = json.decodeFromString(
            ChatUploadResult.serializer(),
            """{"id":9,"url":"/api/chat/files/9","mimeType":"image/webp"}""",
        )
        assertNull(result.width)
        assertNull(result.thumbnailUrl)
    }
}

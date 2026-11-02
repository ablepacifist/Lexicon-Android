package com.alexdyakin.lexicon.data.bridge

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bridge identifies an image uploader by the session token it issued on auth_ok, sent as a bearer
 * token. These pin the request that goes over the wire.
 */
class ChatUploadRequestTest {

    private fun request(token: String?) = ChatUploader.buildUploadRequest(
        url = "https://voice.example/api/chat/upload",
        sessionToken = token,
        bytes = byteArrayOf(1, 2, 3, 4),
        mime = "image/png",
        filename = "cat.png",
        userId = 7,
        username = "alex",
        channelId = 3,
    )

    private fun bodyText(token: String?): String {
        val buffer = Buffer()
        request(token).body!!.writeTo(buffer)
        return buffer.readUtf8()
    }

    @Test
    fun `the session token is sent as a bearer token`() {
        assertEquals("Bearer sess-abc", request("sess-abc").header("Authorization"))
    }

    @Test
    fun `no Authorization header without a token`() {
        assertNull(request(null).header("Authorization"))
        assertNull(request("").header("Authorization"))
    }

    @Test
    fun `it is a POST to the given url with the fields the bridge reads`() {
        val req = request("sess-abc")
        assertEquals("POST", req.method)
        assertEquals("https://voice.example/api/chat/upload", req.url.toString())
        val body = bodyText("sess-abc")
        assertTrue(body.contains("name=\"file\"; filename=\"cat.png\""))
        assertTrue(body.contains("name=\"channelId\""))
        // still sent so an older bridge keeps working; a current bridge ignores them when a token is present
        assertTrue(body.contains("name=\"username\""))
        assertTrue(body.contains("name=\"userId\""))
    }

    @Test
    fun `the token never appears in the body`() {
        assertTrue(!bodyText("sess-abc").contains("sess-abc"))
    }
}

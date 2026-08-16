package com.alexdyakin.lexicon.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `platform: "mobile"` is what makes LexiconServer issue a bearer token, and it
 * is declared as a default value on LoginRequest. kotlinx.serialization drops
 * defaults unless encodeDefaults is on — which silently broke sign-in once
 * already, with no client-side error to point at it.
 */
class LoginRequestSerializationTest {

    /** Must mirror ServiceLocator's configuration. */
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = true
    }

    @Test
    fun `login body carries the mobile platform flag`() {
        val body = json.encodeToString(LoginRequest(username = "someone", password = "secret"))

        assertTrue(
            "platform must be sent or the server issues no mobileToken. Body was: $body",
            body.contains("\"platform\":\"mobile\""),
        )
    }

    @Test
    fun `login body carries username, password and rememberMe`() {
        val body = json.encodeToString(LoginRequest(username = "someone", password = "secret"))

        assertTrue(body, body.contains("\"username\":\"someone\""))
        assertTrue(body, body.contains("\"password\":\"secret\""))
        assertTrue(body, body.contains("\"rememberMe\":true"))
    }

    @Test
    fun `login response parses the mobile token`() {
        val parsed = json.decodeFromString<LoginResponse>(
            """{"success":true,"playerId":7,"id":7,"username":"someone",
               "displayName":"Someone","email":"a@b.c","level":3,"mobileToken":"abc123"}"""
        )

        assertTrue(parsed.mobileToken == "abc123")
        assertTrue(parsed.id == 7)
    }

    @Test
    fun `login response without a mobile token is still parsed`() {
        // The web login path returns no mobileToken; the app must not crash on it
        val parsed = json.decodeFromString<LoginResponse>(
            """{"success":true,"id":7,"username":"someone"}"""
        )

        assertTrue(parsed.mobileToken == null)
    }

    @Test
    fun `playback position body matches the deployed controller contract`() {
        val body = json.encodeToString(
            PlaybackPositionRequest(
                userId = 7,
                mediaFileId = 42,
                position = 123.5,
                duration = 600.0,
                completed = false,
            )
        )

        assertTrue(body, body.contains("\"position\":123.5"))
        assertTrue(body, body.contains("\"duration\":600.0"))
        assertTrue(body, body.contains("\"completed\":false"))
    }
}

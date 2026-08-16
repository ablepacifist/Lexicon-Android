package com.alexdyakin.lexicon.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * The live stream played nothing at all because `currentStartTime` arrives as an ISO date
 * STRING while the model declared it `Long`. kotlinx.serialization threw on the mismatch, the
 * decode of the whole `LiveStreamState` failed, and the SSE handlers — which do
 * `?.let(::decodeState) ?: return` — silently bailed. No error surfaced anywhere; the screen
 * just said "No music currently playing" forever while the website played fine.
 *
 * These payloads are trimmed copies of real responses captured from api.alex-dyakin.com.
 */
class LiveStreamStateSerializationTest {

    /** Must mirror NetworkModule's configuration. */
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = true
    }

    private val realStateResponse = """
        {"success":true,"state":{"id":2,"channel":"music","currentMediaId":547,
        "currentStartTime":"2026-08-16T07:25:51.903915","currentPositionMs":0,
        "totalSkipVotes":0,"requiredSkipVotes":1,
        "currentMedia":{"id":547,"filename":"download.mp3","originalFilename":"download.mp3",
        "contentType":"audio/mpeg","fileSize":15763869,"uploadedBy":1,
        "title":"Stellaris Suite: Creation And Beyond","description":"","mediaType":"MUSIC"}}}
    """.trimIndent().replace("\n", "")

    @Test
    fun `state response with an ISO timestamp decodes instead of throwing`() {
        val decoded = json.decodeFromString(LiveStreamStateResponse.serializer(), realStateResponse)

        assertTrue(decoded.success)
        assertEquals(547, decoded.state.currentMediaId)
        assertNotNull(
            "currentMedia must survive decoding — without it applyStreamToPlayer returns early " +
                "and nothing ever plays",
            decoded.state.currentMedia,
        )
        assertEquals("Stellaris Suite: Creation And Beyond", decoded.state.currentMedia?.title)
    }

    @Test
    fun `ISO timestamp becomes usable epoch millis, not zero`() {
        val decoded = json.decodeFromString(LiveStreamStateResponse.serializer(), realStateResponse)

        val expected = Calendar.getInstance(TimeZone.getDefault()).apply {
            clear()
            set(2026, Calendar.AUGUST, 16, 7, 25, 51)
            set(Calendar.MILLISECOND, 903)
        }.timeInMillis

        assertEquals(
            "A wrong epoch here makes the late-joiner seek jump to a nonsense position",
            expected,
            decoded.state.currentStartTime,
        )
    }

    @Test
    fun `epoch millis sent as a number still decodes`() {
        val payload = """{"channel":"music","currentMediaId":5,"currentStartTime":1755332751903}"""

        val decoded = json.decodeFromString(LiveStreamState.serializer(), payload)

        assertEquals(1755332751903L, decoded.currentStartTime)
    }

    @Test
    fun `a missing or unparseable timestamp degrades to zero rather than throwing`() {
        val missing = json.decodeFromString(LiveStreamState.serializer(), """{"channel":"music"}""")
        assertEquals(0L, missing.currentStartTime)

        val garbage = json.decodeFromString(
            LiveStreamState.serializer(),
            """{"channel":"music","currentStartTime":"not a date"}""",
        )
        assertEquals(0L, garbage.currentStartTime)
    }

    @Test
    fun `microsecond precision is not misread as extra milliseconds`() {
        // SimpleDateFormat's S pattern would read 903915 as 903,915 ms — about 15 minutes late.
        val withMicros = parseTimestampMillis("2026-08-16T07:25:51.903915")
        val withMillis = parseTimestampMillis("2026-08-16T07:25:51.903")

        assertEquals(withMillis, withMicros)
    }

    @Test
    fun `an explicit UTC zone is honoured`() {
        val utc = parseTimestampMillis("2026-08-16T07:25:51Z")

        val expected = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(2026, Calendar.AUGUST, 16, 7, 25, 51)
        }.timeInMillis

        assertEquals(expected, utc)
    }
}

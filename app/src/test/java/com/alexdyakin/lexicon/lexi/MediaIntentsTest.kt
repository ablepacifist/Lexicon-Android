package com.alexdyakin.lexicon.lexi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Ported from Lexi's `tests/test_intents.py`, so the phone understands exactly what the Pi
 * understands, plus the phrasings a phone's speech recogniser tends to produce.
 */
class MediaIntentsTest {

    @Test
    fun `stop and skip`() {
        assertEquals(MediaIntent.Stop, matchMediaIntent("stop"))
        assertEquals(MediaIntent.Stop, matchMediaIntent("stop the music"))
        assertEquals(MediaIntent.Stop, matchMediaIntent("pause"))
        assertEquals(MediaIntent.Next, matchMediaIntent("skip"))
        assertEquals(MediaIntent.Next, matchMediaIntent("next song"))
        assertEquals(MediaIntent.Next, matchMediaIntent("hey jarvis, skip this track"))
    }

    @Test
    fun `the live stream beats a track called live stream`() {
        // "play the live stream" must join the stream, not search for a track by that
        // name — which is why the live-stream pattern is tested before the play pattern.
        for (phrase in listOf(
            "play the live stream",
            "play livestream",
            "put on the radio",
            "join the live stream",
            "tune in to the station",
        )) {
            assertEquals(phrase, MediaIntent.LiveStream, matchMediaIntent(phrase))
        }
    }

    @Test
    fun `a single track still works`() {
        assertEquals(MediaIntent.PlayTrack("dragon rider"), matchMediaIntent("play dragon rider"))
        // The longer opening must win: on the Pi this searches for "me dragon rider".
        assertEquals(MediaIntent.PlayTrack("dragon rider"), matchMediaIntent("play me dragon rider"))
        assertEquals(MediaIntent.PlayTrack("dragon rider"), matchMediaIntent("start playing dragon rider"))
        assertEquals(MediaIntent.PlayTrack("dragon rider"), matchMediaIntent("put on dragon rider"))
    }

    @Test
    fun `shuffling the whole library`() {
        assertEquals(
            MediaIntent.PlayCollection(query = null, shuffle = true, all = true),
            matchMediaIntent("shuffle all my music"),
        )
        assertEquals(
            MediaIntent.PlayCollection(query = null, shuffle = true, all = true),
            matchMediaIntent("shuffle everything"),
        )
    }

    @Test
    fun `shuffling and playing a named playlist`() {
        assertEquals(
            MediaIntent.PlayCollection(query = "Stellaris", shuffle = true, all = false),
            matchMediaIntent("shuffle Stellaris"),
        )
        assertEquals(
            MediaIntent.PlayCollection(query = "Bane", shuffle = true, all = false),
            matchMediaIntent("play the playlist Bane on shuffle"),
        )
        assertEquals(
            MediaIntent.PlayCollection(query = "Bane", shuffle = false, all = false),
            matchMediaIntent("play the playlist Bane"),
        )
    }

    @Test
    fun `play X on shuffle is a collection, not a track`() {
        assertEquals(
            MediaIntent.PlayCollection(query = "Stellaris", shuffle = true, all = false),
            matchMediaIntent("play Stellaris on shuffle"),
        )
    }

    @Test
    fun `questions fall through to the brain`() {
        assertNull(matchMediaIntent("what's the weather in Rosemount"))
        assertNull(matchMediaIntent("who won the game last night"))
        assertNull(matchMediaIntent("what time is it"))
        assertNull(matchMediaIntent("set a ten minute timer"))
        // Nothing to play: better answered by the brain than guessed at.
        assertNull(matchMediaIntent("play"))
    }

    @Test
    fun `speech recognition leaves no punctuation and odd casing`() {
        // Vosk returns lower-case words with no punctuation at all, unlike Whisper on the Pi.
        assertEquals(MediaIntent.Stop, matchMediaIntent("stop the music"))
        // Casing is preserved for the search; only the command words are case-insensitive.
        assertEquals(MediaIntent.PlayTrack("Dragon Rider"), matchMediaIntent("Play Dragon Rider."))
        assertEquals(MediaIntent.Next, matchMediaIntent("Next!"))
    }
}

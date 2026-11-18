package com.alexdyakin.lexicon.lexi

import com.alexdyakin.lexicon.data.LiveStreamState
import com.alexdyakin.lexicon.data.MediaFile
import com.alexdyakin.lexicon.data.SkipStreamRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The spoken media commands: what plays, what Lexi says, and what happens when the library
 * cannot be reached. These run without the brain on purpose — that is the whole point of
 * handling them locally.
 */
class LexiMediaTest {

    private fun track(id: Int, title: String, type: String = "MUSIC") =
        MediaFile(id = id, title = title, mediaType = type)

    private class FakePlayback : VoicePlayback {
        var queue: List<MediaFile> = emptyList()
        var shuffled = false
        var startedAtMs = 0L
        var skips = 0
        var stops = 0
        override var isPlaying = false

        override suspend fun play(tracks: List<MediaFile>, shuffle: Boolean, startPositionMs: Long) {
            queue = tracks
            shuffled = shuffle
            startedAtMs = startPositionMs
            isPlaying = true
        }

        override suspend fun next() { skips++ }
        override suspend fun stop() { stops++; isPlaying = false; queue = emptyList() }
    }

    private class FakeLibrary(
        override val userId: Int? = 7,
        val results: List<MediaFile> = emptyList(),
        val owned: List<MediaFile> = emptyList(),
        val namedPlaylists: List<NamedPlaylist> = emptyList(),
        val playlistContents: Map<Int, List<MediaFile>> = emptyMap(),
        val stream: LiveStreamState = LiveStreamState(),
        val fail: Boolean = false,
    ) : VoiceLibrary {
        var skipVotes = 0

        private fun <T> guard(value: T): T = if (fail) throw IOException("library down") else value

        override suspend fun search(query: String) = guard(results)
        override suspend fun mediaById(id: Int) = guard(owned.firstOrNull { it.id == id })
        override suspend fun ownedMedia(userId: Int) = guard(owned)
        override suspend fun playlists(userId: Int) = guard(namedPlaylists)
        override suspend fun playlistTracks(playlistId: Int) = guard(playlistContents[playlistId].orEmpty())
        override suspend fun liveStreamState() = guard(stream)
        override suspend fun voteToSkipLiveStream(request: SkipStreamRequest) {
            guard(Unit)
            skipVotes++
        }
    }

    private class FakeFollower : LiveStreamFollowing {
        var following = false
        private var onTrack: (suspend (MediaFile) -> Unit)? = null

        override fun start(onTrack: suspend (MediaFile) -> Unit) {
            following = true
            this.onTrack = onTrack
        }

        override fun stop() {
            following = false
            onTrack = null
        }

        /** Pretends the room moved on to another track. */
        suspend fun roomMovedTo(track: MediaFile) = onTrack?.invoke(track)
    }

    private class Session(
        library: FakeLibrary = FakeLibrary(),
        val playback: FakePlayback = FakePlayback(),
        val follower: FakeFollower = FakeFollower(),
    ) {
        val library = library
        val media = LexiMedia(library, playback, follower)
        val said = mutableListOf<String>()

        suspend fun run(text: String): String {
            val intent = matchMediaIntent(text) ?: error("not a media command: $text")
            return media.handle(intent) { said += it }
        }
    }

    // ── playing ──────────────────────────────────────────────────────────────────────

    @Test
    fun `plays a track by name and says which`() = runBlocking {
        val session = Session(FakeLibrary(results = listOf(track(4, "Dragon Rider"))))
        session.run("play dragon rider")

        assertEquals(listOf(4), session.playback.queue.map { it.id })
        assertEquals(listOf("Playing Dragon Rider."), session.said)
        assertFalse(session.playback.shuffled)
    }

    @Test
    fun `prefers music over other media with the same name`() = runBlocking {
        val session = Session(
            FakeLibrary(
                results = listOf(
                    track(1, "Bane", type = "VIDEO"),
                    track(2, "Bane", type = "MUSIC"),
                ),
            ),
        )
        session.run("play bane")
        assertEquals(listOf(2), session.playback.queue.map { it.id })
    }

    @Test
    fun `falls back to the closest title when speech recognition mis-heard it`() = runBlocking {
        // Vosk hears "dragon writer"; search finds nothing, so the library is searched by ear.
        val session = Session(
            FakeLibrary(results = emptyList(), owned = listOf(track(9, "Dragon Rider"))),
        )
        session.run("play dragon writer")

        assertEquals(listOf(9), session.playback.queue.map { it.id })
        assertEquals(listOf("Playing Dragon Rider."), session.said)
    }

    @Test
    fun `says so when nothing matches at all`() = runBlocking {
        val session = Session(FakeLibrary(owned = listOf(track(9, "Completely Different"))))
        session.run("play dragon rider")

        assertTrue(session.playback.queue.isEmpty())
        assertEquals(listOf("I couldn't find dragon rider in your library."), session.said)
    }

    @Test
    fun `shuffles the whole library`() = runBlocking {
        val session = Session(
            FakeLibrary(
                owned = listOf(track(1, "A"), track(2, "B"), track(3, "Home Video", type = "VIDEO")),
            ),
        )
        session.run("shuffle all my music")

        // Videos are not music; they have no business in a shuffle of it.
        assertEquals(listOf(1, 2), session.playback.queue.map { it.id })
        assertTrue(session.playback.shuffled)
        assertEquals(listOf("Shuffling your music."), session.said)
    }

    @Test
    fun `shuffles a playlist found by an approximate name`() = runBlocking {
        val session = Session(
            FakeLibrary(
                namedPlaylists = listOf(NamedPlaylist(3, "Stellaris Soundtrack")),
                playlistContents = mapOf(3 to listOf(track(5, "Creation"), track(6, "Distant Worlds"))),
            ),
        )
        session.run("shuffle stellaris")

        assertEquals(listOf(5, 6), session.playback.queue.map { it.id })
        assertTrue(session.playback.shuffled)
        assertEquals(listOf("Shuffling Stellaris Soundtrack."), session.said)
    }

    @Test
    fun `says so when the playlist does not exist`() = runBlocking {
        val session = Session(FakeLibrary(namedPlaylists = listOf(NamedPlaylist(1, "Bane"))))
        session.run("play the playlist Nonexistent Thing")

        assertTrue(session.playback.queue.isEmpty())
        assertEquals(listOf("I couldn't find a playlist called Nonexistent Thing."), session.said)
    }

    @Test
    fun `says so when the playlist is empty`() = runBlocking {
        val session = Session(
            FakeLibrary(namedPlaylists = listOf(NamedPlaylist(2, "Empty")), playlistContents = mapOf(2 to emptyList())),
        )
        session.run("play the playlist Empty")

        assertTrue(session.playback.queue.isEmpty())
        assertEquals(listOf("There's nothing to play in Empty."), session.said)
    }

    // ── stop and skip ────────────────────────────────────────────────────────────────

    @Test
    fun `stop stops`() = runBlocking {
        val session = Session()
        session.playback.isPlaying = true
        session.run("stop")

        assertEquals(1, session.playback.stops)
        assertEquals(listOf("Okay, stopped."), session.said)
    }

    @Test
    fun `skip advances rather than stopping`() = runBlocking {
        val session = Session()
        session.playback.isPlaying = true
        session.run("skip")

        assertEquals(1, session.playback.skips)
        assertEquals(0, session.playback.stops)
        assertEquals(listOf("Skipping."), session.said)
    }

    @Test
    fun `skip with nothing playing says so`() = runBlocking {
        val session = Session()
        session.run("next")

        assertEquals(0, session.playback.skips)
        assertEquals(listOf("Nothing is playing."), session.said)
    }

    // ── the communal live stream ─────────────────────────────────────────────────────

    @Test
    fun `joins the live stream from the start and follows it`() = runBlocking {
        val current = track(11, "Communal Track")
        val session = Session(FakeLibrary(stream = LiveStreamState(currentMediaId = 11, currentMedia = current)))
        session.run("play the live stream")

        assertEquals(listOf(11), session.playback.queue.map { it.id })
        // Never seeks: a stale server position would play silence past the end of the track.
        assertEquals(0L, session.playback.startedAtMs)
        assertTrue("should be following the room", session.follower.following)

        // When the room moves on, so does the phone.
        session.follower.roomMovedTo(track(12, "Next Communal Track"))
        assertEquals(listOf(12), session.playback.queue.map { it.id })
    }

    @Test
    fun `skip during the live stream votes instead of skipping locally`() = runBlocking {
        val session = Session(
            FakeLibrary(stream = LiveStreamState(currentMediaId = 11, currentMedia = track(11, "Communal"))),
        )
        session.run("play the live stream")
        session.said.clear()
        session.run("skip")

        assertEquals(1, session.library.skipVotes)
        assertEquals(0, session.playback.skips)
        assertEquals(listOf("Voting to skip."), session.said)
    }

    @Test
    fun `playing something else leaves the live stream`() = runBlocking {
        val session = Session(
            FakeLibrary(
                results = listOf(track(4, "Dragon Rider")),
                stream = LiveStreamState(currentMediaId = 11, currentMedia = track(11, "Communal")),
            ),
        )
        session.run("play the live stream")
        session.run("play dragon rider")

        assertFalse("must stop following", session.follower.following)
        // And a later skip is a local skip again, not a vote.
        session.playback.isPlaying = true
        session.run("skip")
        assertEquals(1, session.playback.skips)
        assertEquals(0, session.library.skipVotes)
    }

    @Test
    fun `says so when the live stream has nothing on`() = runBlocking {
        val session = Session(FakeLibrary(stream = LiveStreamState()))
        session.run("join the live stream")

        assertTrue(session.playback.queue.isEmpty())
        assertEquals(listOf("Sorry, the live stream isn't playing anything right now."), session.said)
        assertFalse(session.follower.following)
    }

    // ── failure ──────────────────────────────────────────────────────────────────────

    @Test
    fun `an unreachable library is explained, not thrown`() = runBlocking {
        val session = Session(FakeLibrary(fail = true))
        val label = session.run("play dragon rider")

        assertEquals("library unreachable", label)
        assertEquals(listOf("Sorry, I couldn't reach the music library."), session.said)
    }

    @Test
    fun `signed out asks the user to sign in`() = runBlocking {
        val session = Session(FakeLibrary(userId = null))
        session.run("play dragon rider")

        assertTrue(session.playback.queue.isEmpty())
        assertEquals(listOf("You'll need to sign in to Lexicon first."), session.said)
    }
}

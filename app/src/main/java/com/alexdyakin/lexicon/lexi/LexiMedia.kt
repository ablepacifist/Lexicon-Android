package com.alexdyakin.lexicon.lexi

import android.util.Log
import com.alexdyakin.lexicon.data.LiveStreamState
import com.alexdyakin.lexicon.data.MediaFile
import com.alexdyakin.lexicon.data.SkipStreamRequest
import com.alexdyakin.lexicon.data.liveStreamStartPosition
import java.io.IOException

/** What Lexi can do to the player. An interface so the rules below are testable off-device. */
interface VoicePlayback {
    val isPlaying: Boolean

    /** Replaces the queue and starts playing. */
    suspend fun play(tracks: List<MediaFile>, shuffle: Boolean = false, startPositionMs: Long = 0L)
    suspend fun next()
    suspend fun stop()
}

/** The library calls Lexi needs. Narrow on purpose: the app's APIs are far larger. */
interface VoiceLibrary {
    /** The signed-in user, or null when nobody is. */
    val userId: Int?

    suspend fun search(query: String): List<MediaFile>
    suspend fun mediaById(id: Int): MediaFile?
    suspend fun ownedMedia(userId: Int): List<MediaFile>
    suspend fun playlists(userId: Int): List<NamedPlaylist>
    suspend fun playlistTracks(playlistId: Int): List<MediaFile>
    suspend fun liveStreamState(): LiveStreamState
    suspend fun voteToSkipLiveStream(request: SkipStreamRequest)
}

data class NamedPlaylist(val id: Int, val name: String)

/**
 * Carries out the spoken media commands, against the app's own player and library.
 *
 * A port of the Pi's `media.py` behaviour, not its mechanism: the Pi shells out to mpv and
 * streams with a session cookie, while the phone already has a background player with a
 * queue, shuffle and lock-screen controls, so this drives that instead.
 *
 * These never reach the brain, which is the point: music keeps working with alison off.
 */
class LexiMedia(
    private val library: VoiceLibrary,
    private val playback: VoicePlayback,
) {
    /** True while the communal stream is what is playing, so "skip" votes instead of skipping. */
    @Volatile private var liveStreamJoined = false

    /** Media commands need a signed-in user to search anyone's library. */
    val enabled: Boolean get() = library.userId != null

    /**
     * Runs [intent], speaking through [say] as it goes, and returns a short label of what
     * happened for the turn history.
     *
     * [say] both speaks and waits, so a confirmation is finished before music starts over it.
     */
    suspend fun handle(intent: MediaIntent, say: suspend (String) -> Unit): String {
        val userId = library.userId ?: run {
            say("You'll need to sign in to Lexicon first.")
            return "not signed in"
        }
        return try {
            when (intent) {
                is MediaIntent.Stop -> stop(say)
                is MediaIntent.Next -> next(say)
                is MediaIntent.LiveStream -> joinLiveStream(say)
                is MediaIntent.PlayTrack -> playTrack(intent.query, userId, say)
                is MediaIntent.PlayCollection -> playCollection(intent, userId, say)
            }
        } catch (e: IOException) {
            // The library lives on aragon; a phone off Wi-Fi or a server restart lands here.
            Log.w(TAG, "media command failed", e)
            say("Sorry, I couldn't reach the music library.")
            "library unreachable"
        }
    }

    private suspend fun stop(say: suspend (String) -> Unit): String {
        liveStreamJoined = false
        playback.stop()
        say("Okay, stopped.")
        return "stopped"
    }

    /**
     * Skip advances rather than stops. During the communal live stream a skip is a vote,
     * because everyone is listening to the same thing.
     */
    private suspend fun next(say: suspend (String) -> Unit): String {
        if (liveStreamJoined) {
            return try {
                val userId = library.userId ?: return "not signed in"
                library.voteToSkipLiveStream(SkipStreamRequest(userId))
                say("Voting to skip.")
                "voted to skip"
            } catch (e: IOException) {
                Log.w(TAG, "live stream skip failed", e)
                say("Sorry, I couldn't skip.")
                "skip failed"
            }
        }
        if (!playback.isPlaying) {
            say("Nothing is playing.")
            return "nothing playing"
        }
        playback.next()
        say("Skipping.")
        return "skipped"
    }

    private suspend fun joinLiveStream(say: suspend (String) -> Unit): String {
        val state = library.liveStreamState()
        // The state carries the track itself most of the time; when it carries only an id
        // (as the screen also finds), fetch it.
        val media = state.currentMedia
            ?: state.currentMediaId.takeIf { it > 0 }?.let { library.mediaById(it) }
        if (media == null) {
            say("Sorry, the live stream isn't playing anything right now.")
            return "live stream empty"
        }
        say("Joining the live stream.")
        // Joins where everyone else is, using the same rule the live stream screen uses.
        playback.play(listOf(media), startPositionMs = liveStreamStartPosition(state))
        liveStreamJoined = true
        return "live stream"
    }

    private suspend fun playTrack(query: String, userId: Int, say: suspend (String) -> Unit): String {
        val chosen = pick(library.search(query)) ?: fuzzyFind(query, userId)
        if (chosen == null) {
            say("I couldn't find $query in your library.")
            return "not found"
        }
        liveStreamJoined = false
        val title = chosen.displayTitle.ifBlank { query }
        say("Playing $title.")
        playback.play(listOf(chosen))
        return title
    }

    private suspend fun playCollection(
        intent: MediaIntent.PlayCollection,
        userId: Int,
        say: suspend (String) -> Unit,
    ): String {
        val (tracks, label) = if (intent.all) {
            library.ownedMedia(userId).filter { it.mediaType.equals(MUSIC, ignoreCase = true) } to "your music"
        } else {
            val name = intent.query.orEmpty()
            val playlist = findPlaylist(name, userId)
            if (playlist == null) {
                say("I couldn't find a playlist called $name.")
                return "no such playlist"
            }
            library.playlistTracks(playlist.id) to playlist.name.ifBlank { name }
        }

        if (tracks.isEmpty()) {
            say("There's nothing to play in $label.")
            return "empty"
        }
        liveStreamJoined = false
        say(if (intent.shuffle) "Shuffling $label." else "Playing $label.")
        playback.play(tracks, shuffle = intent.shuffle)
        return label
    }

    /** The Pi's rule: prefer music, then audiobooks, else whatever came back first. */
    private fun pick(items: List<MediaFile>): MediaFile? =
        items.firstOrNull { it.mediaType.equals(MUSIC, ignoreCase = true) }
            ?: items.firstOrNull { it.mediaType.contains(AUDIOBOOK, ignoreCase = true) }
            ?: items.firstOrNull()

    /**
     * Fallback for when the search misses because speech recognition mis-heard the title —
     * which it does often enough that the Pi needed this too.
     */
    private suspend fun fuzzyFind(query: String, userId: Int): MediaFile? {
        val owned = library.ownedMedia(userId)
        val candidates = owned.filter {
            it.mediaType.equals(MUSIC, ignoreCase = true) || it.mediaType.contains(AUDIOBOOK, ignoreCase = true)
        }.ifEmpty { owned }
        return candidates
            .map { it to similarity(query, it.displayTitle) }
            .filter { it.second >= MATCH_THRESHOLD }
            .maxByOrNull { it.second }
            ?.first
    }

    private suspend fun findPlaylist(name: String, userId: Int): NamedPlaylist? {
        val playlists = library.playlists(userId)
        val wanted = name.trim().lowercase()
        return playlists.firstOrNull { it.name.lowercase() == wanted }
            ?: playlists.firstOrNull { wanted in it.name.lowercase() }
            ?: playlists
                .map { it to similarity(name, it.name) }
                .filter { it.second >= MATCH_THRESHOLD }
                .maxByOrNull { it.second }
                ?.first
    }

    private companion object {
        const val TAG = "LexiMedia"
        const val MUSIC = "MUSIC"
        const val AUDIOBOOK = "AUDIOBOOK"

        /** The Pi's difflib cutoff, kept so both devices accept and reject the same titles. */
        const val MATCH_THRESHOLD = 0.6
    }
}

/**
 * How alike two titles are, 0..1.
 *
 * Stands in for Python's `difflib.SequenceMatcher.ratio()`, which the Pi uses: edit distance
 * over the longer string, plus the same reward for one containing the other, so a partly
 * mis-heard title still wins.
 */
internal fun similarity(a: String, b: String): Double {
    val left = a.trim().lowercase()
    val right = b.trim().lowercase()
    if (left.isEmpty() || right.isEmpty()) return 0.0
    if (left == right) return 1.0
    if (left in right || right in left) return maxOf(0.85, ratio(left, right))
    return ratio(left, right)
}

private fun ratio(a: String, b: String): Double {
    val distance = editDistance(a, b)
    val longest = maxOf(a.length, b.length)
    return 1.0 - distance.toDouble() / longest
}

private fun editDistance(a: String, b: String): Int {
    // Two rows rather than a full matrix: titles are short, but so is the phone's battery.
    var previous = IntArray(b.length + 1) { it }
    var current = IntArray(b.length + 1)
    for (i in 1..a.length) {
        current[0] = i
        for (j in 1..b.length) {
            val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = minOf(current[j - 1] + 1, previous[j] + 1, substitution)
        }
        val swap = previous
        previous = current
        current = swap
    }
    return previous[b.length]
}

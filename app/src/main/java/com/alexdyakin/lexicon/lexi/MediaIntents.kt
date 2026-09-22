package com.alexdyakin.lexicon.lexi

/**
 * The handful of spoken commands Lexi answers itself, without the brain.
 *
 * A port of `lexi/intents.py`, and deliberately small and literal: only phrasings that
 * should never round-trip to Obrenna match here, and everything else falls through to it.
 * That is what keeps music working when alison is offline.
 */
sealed interface MediaIntent {
    data object Stop : MediaIntent
    data object Next : MediaIntent
    /** Join the communal live stream everyone hears together. */
    data object LiveStream : MediaIntent
    data class PlayTrack(val query: String) : MediaIntent
    /** A playlist by name, or the whole library when [all] is set. */
    data class PlayCollection(val query: String?, val shuffle: Boolean, val all: Boolean) : MediaIntent
}

// An optional leading wake word ("hey jarvis ..."), in case part of it lands in the
// transcript, plus a polite prefix.
private const val LEAD = """^\s*(?:hey\s+\w+[,.\s]+)?(?:can you\s+|could you\s+|please\s+)?"""
private const val TRAIL = """[.?!]*\s*$"""

private val IGNORE_CASE = setOf(RegexOption.IGNORE_CASE)

private val STOP = Regex(
    LEAD + """(?:stop|pause)(?:\s+(?:the\s+)?(?:music|song|playback|audio|it))?""" + TRAIL,
    IGNORE_CASE,
)
private val NEXT = Regex(
    LEAD + """(?:next|skip)(?:\s+(?:this\s+|the\s+)?(?:song|track|one))?""" + TRAIL,
    IGNORE_CASE,
)

// "play/join/tune in to [the] live stream / radio / station". Must be tested before PLAY,
// or "play the live stream" is read as a track title.
private val LIVE_STREAM = Regex(
    LEAD + """(?:play|put on|start|join|tune in ?to)\s+(?:the\s+)?(?:live\s*stream|radio|station)""" + TRAIL,
    IGNORE_CASE,
)

/** "shuffle [the] [playlist] X" or "shuffle my music / everything / all". */
private val SHUFFLE = Regex(LEAD + """shuffle\s+(?:the\s+)?(?:playlist\s+)?(.+?)""" + TRAIL, IGNORE_CASE)

/** "play [the] playlist X [on shuffle|shuffled]". */
private val PLAYLIST = Regex(
    LEAD + """(?:play|put on|start)\s+(?:the\s+)?playlist\s+(.+?)(\s+(?:on\s+shuffle|shuffled))?""" + TRAIL,
    IGNORE_CASE,
)

/**
 * "play X [on shuffle|shuffled]" — one track, unless the shuffle suffix is there.
 *
 * The longer openings come first on purpose. Alternation is leftmost-first, so listing
 * bare "play" earlier (as `intents.py` does) makes "play me dragon rider" match "play" and
 * search for a track called "me dragon rider". Worth fixing on the Pi too.
 */
private val PLAY = Regex(
    LEAD + """(?:start playing|play some|play me|put on|play)\s+(.+?)(\s+(?:on\s+shuffle|shuffled))?""" + TRAIL,
    IGNORE_CASE,
)

private val ALL_MUSIC = setOf(
    "my music", "everything", "all", "music", "all music", "my library", "all my music",
)

/** Returns the command this transcript is, or null to let the brain answer it. */
fun matchMediaIntent(text: String): MediaIntent? {
    if (STOP.containsMatchIn(text)) return MediaIntent.Stop
    if (NEXT.containsMatchIn(text)) return MediaIntent.Next
    if (LIVE_STREAM.containsMatchIn(text)) return MediaIntent.LiveStream

    SHUFFLE.find(text)?.let { match ->
        val target = match.groupValues[1].trim()
        val isAll = target.lowercase() in ALL_MUSIC
        return MediaIntent.PlayCollection(
            query = if (isAll) null else target,
            shuffle = true,
            all = isAll,
        )
    }

    PLAYLIST.find(text)?.let { match ->
        return MediaIntent.PlayCollection(
            query = match.groupValues[1].trim(),
            shuffle = match.groupValues[2].isNotEmpty(),
            all = false,
        )
    }

    PLAY.find(text)?.let { match ->
        val query = match.groupValues[1].trim()
        if (query.isEmpty()) return null
        // "play X on shuffle", with no "playlist" keyword, means a collection.
        return if (match.groupValues[2].isNotEmpty()) {
            MediaIntent.PlayCollection(query = query, shuffle = true, all = false)
        } else {
            MediaIntent.PlayTrack(query)
        }
    }

    return null
}

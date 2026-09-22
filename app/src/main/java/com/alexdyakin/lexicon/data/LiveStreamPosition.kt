package com.alexdyakin.lexicon.data

/**
 * Where a listener joining the communal live stream should start playing.
 *
 * Shared by the live stream screen and by Lexi, so a voice "join the live stream" lands at
 * the same point in the track as opening the screen would.
 */

/**
 * Past this, the server's idea of the position is not to be believed: nobody has been
 * listening, so no client has reported `media-ended` and `currentStartTime` has gone stale.
 */
const val LIVE_STREAM_STALE_POSITION_MS = 3 * 60 * 60 * 1000L

/** `currentPositionMs + (now - currentStartTime)`, matching the web's `calculateCurrentPosition`. */
fun liveStreamServerPosition(
    state: LiveStreamState,
    nowMs: Long = System.currentTimeMillis(),
): Long {
    if (state.currentStartTime <= 0L) return state.currentPositionMs.coerceAtLeast(0L)
    return (state.currentPositionMs + (nowMs - state.currentStartTime)).coerceAtLeast(0L)
}

/**
 * The web rule is "seek to the live position, but ignore an offset under 15s — that is just
 * loading latency", with one guard the web lacks: a stale `currentStartTime` can compute a
 * position far past the end of the track (observed: 17+ minutes into a 4-minute song), which
 * would end the track instantly and play no audio at all. Beyond that bound, start at the
 * beginning and let normal `media-ended` reporting resynchronise the server.
 */
fun liveStreamStartPosition(
    state: LiveStreamState,
    nowMs: Long = System.currentTimeMillis(),
): Long {
    val position = liveStreamServerPosition(state, nowMs)
    if (position <= 15_000L) return 0L
    if (position > LIVE_STREAM_STALE_POSITION_MS) return 0L
    return position
}

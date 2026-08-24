package com.alexdyakin.lexicon.data.bridge

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The audio maths for the voice bridge, kept free of Android types so it can be tested
 * on the JVM.
 *
 * Contrary to what this app used to claim, no Opus codec is needed here: the bridge
 * encodes and decodes server-side and the socket carries raw PCM in both directions.
 * What is needed is exact framing, correct byte order, and a mixer — and every one of
 * those fails *silently* when it is wrong, which is why they live here behind tests
 * rather than inline in a service.
 */

/** 48 kHz mono, 20 ms per frame, as the bridge's encoder is configured. */
const val SAMPLE_RATE = 48_000
const val SAMPLES_PER_FRAME = 960
const val BYTES_PER_FRAME = SAMPLES_PER_FRAME * 2

/**
 * Cuts a stream of captured bytes into frames the server will actually encode.
 *
 * The bridge's uplink loop is `offset + 960 <= int16.length`, so a partial frame is
 * **discarded, not buffered** — send 1000 bytes and they are gone, with no error. Worse,
 * `AudioRecord`'s natural buffer size is not a multiple of 960 samples, so writing each
 * read straight to the socket would silently drop a sliver of every single one. This
 * accumulates instead and only ever emits exact multiples.
 */
class PcmFramer {
    private var pending = ByteArray(0)

    /** Bytes held back because they do not yet complete a frame. */
    val pendingBytes: Int get() = pending.size

    /**
     * Adds [count] bytes from [chunk] and returns whatever whole frames that completes.
     * Returns an empty list rather than a partial frame.
     */
    fun offer(chunk: ByteArray, count: Int = chunk.size): List<ByteArray> {
        if (count <= 0) return emptyList()

        val combined = ByteArray(pending.size + count)
        pending.copyInto(combined)
        chunk.copyInto(combined, pending.size, 0, count)

        val frameCount = combined.size / BYTES_PER_FRAME
        if (frameCount == 0) {
            pending = combined
            return emptyList()
        }

        val frames = ArrayList<ByteArray>(frameCount)
        for (i in 0 until frameCount) {
            frames += combined.copyOfRange(i * BYTES_PER_FRAME, (i + 1) * BYTES_PER_FRAME)
        }
        pending = combined.copyOfRange(frameCount * BYTES_PER_FRAME, combined.size)
        return frames
    }

    /** Drops anything held. Used when a session ends so stale audio cannot leak in. */
    fun reset() {
        pending = ByteArray(0)
    }
}

/** One decoded downlink packet: PCM from a single speaker. */
data class VoicePacket(val session: Long, val pcm: ShortArray) {
    // ShortArray gives identity equals by default, which makes these useless in tests
    // and in any set or map.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VoicePacket) return false
        return session == other.session && pcm.contentEquals(other.pcm)
    }

    override fun hashCode(): Int = 31 * session.hashCode() + pcm.contentHashCode()
}

/**
 * Parses `[4-byte UInt32LE session][Int16LE PCM]`.
 *
 * The sample count comes from the actual length rather than being assumed to be 960:
 * native Mumble clients can use 40 ms or 60 ms frames, and those arrive here unchanged.
 * A packet with no payload, or an odd payload length, is rejected rather than being
 * padded into noise.
 */
fun parseVoicePacket(bytes: ByteArray): VoicePacket? {
    if (bytes.size <= 4) return null
    val payload = bytes.size - 4
    if (payload % 2 != 0) return null

    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    // Read as Int and widen, because Kotlin has no unsigned 32-bit read here and a
    // session id above 2^31 would otherwise come back negative.
    val session = buffer.int.toLong() and 0xFFFF_FFFFL
    val pcm = ShortArray(payload / 2)
    for (i in pcm.indices) pcm[i] = buffer.short
    return VoicePacket(session, pcm)
}

/**
 * Packs PCM samples for the uplink.
 *
 * [ByteOrder.LITTLE_ENDIAN] is set explicitly: `ByteBuffer` defaults to big-endian, and
 * the resulting byte-swapped samples would be transmitted as white noise rather than
 * failing in any visible way.
 */
fun encodePcm(samples: ShortArray): ByteArray {
    val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    samples.forEach { buffer.putShort(it) }
    return buffer.array()
}

/**
 * Sums several speakers into one stream.
 *
 * The bridge does **not** mix — each speaker arrives on its own, so summing is the
 * client's job. Two people at normal volume routinely exceed a 16-bit sample, and
 * letting that wrap turns loud moments into a loud crack, so the sum is clamped.
 * Shorter inputs are treated as silence past their end rather than being dropped, which
 * is what happens when one speaker's buffer runs dry mid-frame.
 */
fun mix(sources: List<ShortArray>): ShortArray {
    if (sources.isEmpty()) return ShortArray(0)
    if (sources.size == 1) return sources[0]

    val length = sources.maxOf { it.size }
    val out = ShortArray(length)
    for (i in 0 until length) {
        var sum = 0
        for (source in sources) {
            if (i < source.size) sum += source[i]
        }
        out[i] = sum.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
    return out
}

/**
 * One speaker's buffered audio.
 *
 * The server paces nothing and drops frames once its send buffer passes ~200 ms, so
 * audio arrives in bursts with gaps rather than smoothly. Playback therefore waits for
 * a small cushion before starting, and does not declare the speaker finished the moment
 * the buffer empties — otherwise every gap would clip the start of the next word.
 */
class JitterBuffer(
    private val startAtSamples: Int = SAMPLE_RATE * 60 / 1000,
    private val capacitySamples: Int = SAMPLE_RATE,
) {
    private val queue = ArrayDeque<Short>()
    private var playing = false

    val buffered: Int get() = queue.size
    val isPlaying: Boolean get() = playing

    /** Adds samples, discarding the oldest beyond capacity so latency cannot grow without bound. */
    fun push(samples: ShortArray) {
        samples.forEach { queue.addLast(it) }
        while (queue.size > capacitySamples) queue.removeFirst()
        if (!playing && queue.size >= startAtSamples) playing = true
    }

    /**
     * Takes exactly [count] samples once playing, padding with silence when the buffer
     * runs short. Returns null while still filling the initial cushion, so a caller can
     * tell "not started yet" from "started but momentarily quiet".
     */
    fun drain(count: Int): ShortArray? {
        if (!playing) return null
        val out = ShortArray(count)
        for (i in 0 until count) {
            out[i] = if (queue.isEmpty()) 0 else queue.removeFirst()
        }
        return out
    }

    /** True once the speaker has been silent long enough to stop mixing them in. */
    fun isDry(): Boolean = playing && queue.isEmpty()

    fun reset() {
        queue.clear()
        playing = false
    }
}

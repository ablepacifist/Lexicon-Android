package com.alexdyakin.lexicon.data.bridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The voice maths.
 *
 * Every failure mode covered here is silent on a real device — wrong byte order plays as
 * noise, a short frame is discarded by the server without an error, an unclamped mix
 * wraps into a crack. None of them throw, so they are pinned here instead.
 */
class VoiceAudioTest {

    // ── framing ──────────────────────────────────────────────────────────────

    /**
     * The server's loop is `offset + 960 <= length`, so a short write is dropped
     * outright. The framer must hold it instead.
     */
    @Test
    fun `a short write emits nothing and is retained`() {
        val framer = PcmFramer()
        assertTrue(framer.offer(ByteArray(1000)).isEmpty())
        assertEquals(1000, framer.pendingBytes)
    }

    @Test
    fun `exactly one frame emits one frame and retains nothing`() {
        val framer = PcmFramer()
        val frames = framer.offer(ByteArray(BYTES_PER_FRAME))
        assertEquals(1, frames.size)
        assertEquals(BYTES_PER_FRAME, frames[0].size)
        assertEquals(0, framer.pendingBytes)
    }

    @Test
    fun `4000 bytes emits two frames and retains the remainder`() {
        val framer = PcmFramer()
        val frames = framer.offer(ByteArray(4000))
        assertEquals(2, frames.size)
        assertEquals(4000 - 2 * BYTES_PER_FRAME, framer.pendingBytes)
        assertEquals(160, framer.pendingBytes)
    }

    /**
     * AudioRecord's buffer is not a multiple of 960 samples, so this is the normal
     * case: repeated odd-sized reads must still produce every sample eventually.
     */
    @Test
    fun `odd sized reads lose no bytes across many writes`() {
        val framer = PcmFramer()
        val readSize = 1024
        var emitted = 0
        repeat(100) { emitted += framer.offer(ByteArray(readSize)).size * BYTES_PER_FRAME }
        assertEquals(100 * readSize, emitted + framer.pendingBytes)
    }

    @Test
    fun `frames carry the original bytes in order`() {
        val framer = PcmFramer()
        val chunk = ByteArray(BYTES_PER_FRAME * 2) { (it % 251).toByte() }
        val frames = framer.offer(chunk)

        assertArrayEquals(chunk.copyOfRange(0, BYTES_PER_FRAME), frames[0])
        assertArrayEquals(chunk.copyOfRange(BYTES_PER_FRAME, BYTES_PER_FRAME * 2), frames[1])
    }

    @Test
    fun `a frame split across two writes is reassembled`() {
        val framer = PcmFramer()
        assertTrue(framer.offer(ByteArray(BYTES_PER_FRAME - 2)).isEmpty())
        assertEquals(1, framer.offer(ByteArray(2)).size)
        assertEquals(0, framer.pendingBytes)
    }

    @Test
    fun `only the requested count is consumed from a larger buffer`() {
        val framer = PcmFramer()
        // AudioRecord hands back a full-size buffer with a shorter valid read.
        assertTrue(framer.offer(ByteArray(BYTES_PER_FRAME * 2), count = 100).isEmpty())
        assertEquals(100, framer.pendingBytes)
    }

    @Test
    fun `reset drops held audio`() {
        val framer = PcmFramer()
        framer.offer(ByteArray(1000))
        framer.reset()
        assertEquals(0, framer.pendingBytes)
    }

    // ── byte order ───────────────────────────────────────────────────────────

    /**
     * ByteBuffer defaults to big-endian. Getting this wrong transmits byte-swapped
     * samples, which sound like white noise but raise nothing.
     */
    @Test
    fun `pcm is encoded little endian`() {
        val encoded = encodePcm(shortArrayOf(0x0102, 0x7FFF, -1))
        assertArrayEquals(
            byteArrayOf(0x02, 0x01, 0xFF.toByte(), 0x7F, 0xFF.toByte(), 0xFF.toByte()),
            encoded,
        )
    }

    @Test
    fun `encode and parse round trip`() {
        val samples = shortArrayOf(0, 1, -1, 32767, -32768, 1234)
        val packet = ByteArray(4) + encodePcm(samples)
        assertArrayEquals(samples, parseVoicePacket(packet)!!.pcm)
    }

    // ── downlink ─────────────────────────────────────────────────────────────

    @Test
    fun `the session prefix is read little endian`() {
        val packet = byteArrayOf(0x2A, 0, 0, 0) + encodePcm(shortArrayOf(5))
        assertEquals(42L, parseVoicePacket(packet)!!.session)
    }

    /** A session id above 2^31 must not come back negative. */
    @Test
    fun `a high session id stays positive`() {
        val packet = byteArrayOf(
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
        ) + encodePcm(shortArrayOf(1))
        assertEquals(4_294_967_295L, parseVoicePacket(packet)!!.session)
    }

    /**
     * Payload length drives the sample count. Native Mumble clients may send 40 ms or
     * 60 ms frames, so assuming 960 samples would truncate them.
     */
    @Test
    fun `a non-1920 payload is parsed at its actual length`() {
        val samples = ShortArray(SAMPLES_PER_FRAME * 3) { (it % 100).toShort() }
        val parsed = parseVoicePacket(ByteArray(4) + encodePcm(samples))!!
        assertEquals(SAMPLES_PER_FRAME * 3, parsed.pcm.size)
        assertArrayEquals(samples, parsed.pcm)
    }

    @Test
    fun `a packet with no payload is rejected`() {
        assertNull(parseVoicePacket(byteArrayOf(1, 0, 0, 0)))
        assertNull(parseVoicePacket(ByteArray(0)))
    }

    @Test
    fun `an odd payload length is rejected rather than padded`() {
        assertNull(parseVoicePacket(ByteArray(4 + 3)))
    }

    // ── mixing ───────────────────────────────────────────────────────────────

    @Test
    fun `a single speaker passes through untouched`() {
        val one = shortArrayOf(1, 2, 3)
        assertArrayEquals(one, mix(listOf(one)))
    }

    @Test
    fun `two speakers are summed`() {
        assertArrayEquals(
            shortArrayOf(300, 30, -300),
            mix(listOf(shortArrayOf(100, 10, -100), shortArrayOf(200, 20, -200))),
        )
    }

    /** Without the clamp this wraps to a large negative value and cracks audibly. */
    @Test
    fun `a positive overflow clamps instead of wrapping`() {
        val loud = shortArrayOf(30000, 30000)
        val mixed = mix(listOf(loud, loud))
        assertArrayEquals(shortArrayOf(32767, 32767), mixed)
    }

    @Test
    fun `a negative overflow clamps at the floor`() {
        val loud = shortArrayOf(-30000)
        assertArrayEquals(shortArrayOf(-32768), mix(listOf(loud, loud)))
    }

    @Test
    fun `a shorter speaker is treated as silence past its end`() {
        assertArrayEquals(
            shortArrayOf(15, 15, 5),
            mix(listOf(shortArrayOf(10, 10), shortArrayOf(5, 5, 5))),
        )
    }

    @Test
    fun `no speakers mixes to nothing`() {
        assertEquals(0, mix(emptyList()).size)
    }

    // ── jitter buffer ────────────────────────────────────────────────────────

    @Test
    fun `playback waits for the initial cushion`() {
        val buffer = JitterBuffer(startAtSamples = 100)
        buffer.push(ShortArray(50))
        assertFalse(buffer.isPlaying)
        assertNull("must not start on a half-filled cushion", buffer.drain(10))

        buffer.push(ShortArray(50))
        assertTrue(buffer.isPlaying)
        assertEquals(10, buffer.drain(10)!!.size)
    }

    /** A gap mid-speech pads with silence rather than stopping, so words are not clipped. */
    @Test
    fun `a short buffer pads with silence and keeps playing`() {
        val buffer = JitterBuffer(startAtSamples = 4)
        buffer.push(shortArrayOf(1, 2, 3, 4))
        assertArrayEquals(shortArrayOf(1, 2, 3, 4, 0, 0), buffer.drain(6))
        assertTrue(buffer.isPlaying)
        assertTrue(buffer.isDry())
    }

    @Test
    fun `capacity is bounded so latency cannot grow without limit`() {
        val buffer = JitterBuffer(startAtSamples = 1, capacitySamples = 100)
        buffer.push(ShortArray(500) { 7 })
        assertEquals(100, buffer.buffered)
    }

    /** The oldest audio is what gets dropped, so playback stays close to live. */
    @Test
    fun `overflow drops the oldest samples`() {
        val buffer = JitterBuffer(startAtSamples = 1, capacitySamples = 3)
        buffer.push(shortArrayOf(1, 2, 3, 4, 5))
        assertArrayEquals(shortArrayOf(3, 4, 5), buffer.drain(3))
    }

    @Test
    fun `a buffer that never started is not dry`() {
        val buffer = JitterBuffer(startAtSamples = 100)
        assertFalse("nothing has played, so there is nothing to stop", buffer.isDry())
    }

    @Test
    fun `reset returns to the filling state`() {
        val buffer = JitterBuffer(startAtSamples = 2)
        buffer.push(shortArrayOf(1, 2))
        buffer.reset()
        assertFalse(buffer.isPlaying)
        assertEquals(0, buffer.buffered)
    }
}

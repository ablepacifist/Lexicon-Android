package com.alexdyakin.lexicon.lexi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The wake-word detector is a hand port of openWakeWord, so it is held to the original.
 *
 * The golden half runs the real ONNX models over clips whose per-chunk scores were
 * recorded from Lexi's Python pipeline (`scripts/lexi_wake_golden.py`), and must
 * reproduce them. The fake half pins the streaming arithmetic directly, so a failure
 * there points at the port rather than at the models.
 */
class WakeWordDetectorTest {

    // ── against openWakeWord itself ──────────────────────────────────────────────────

    @Test
    fun `reproduces openWakeWord's scores chunk for chunk`() {
        for (clip in listOf("hey_jarvis", "negative")) {
            val expected = golden.getValue(clip)
            val actual = scoresPerChunk(WakeWordDetector(models), readClip(clip), readSize = WAKE_CHUNK_SAMPLES)
            assertEquals(expected.size, actual.size)

            val warmUp = models.featureFrames - 1
            for (i in actual.indices) {
                if (i < warmUp) {
                    assertEquals("$clip chunk $i should still be warming up", 0f, actual[i], 0f)
                } else {
                    assertEquals("$clip chunk $i", expected[i], actual[i], 1e-4f)
                }
            }
        }
    }

    @Test
    fun `hears the wake word and ignores other speech`() {
        val heard = scoresPerChunk(WakeWordDetector(models), readClip("hey_jarvis"), WAKE_CHUNK_SAMPLES)
        val other = scoresPerChunk(WakeWordDetector(models), readClip("negative"), WAKE_CHUNK_SAMPLES)
        assertTrue("peak was ${heard.max()}", heard.max() > 0.9f)
        assertTrue("peak was ${other.max()}", other.max() < 0.05f)
    }

    @Test
    fun `scores do not depend on how the microphone slices the audio`() {
        // AudioRecord hands back whatever it has; 1000 is deliberately not a chunk multiple.
        val clip = readClip("hey_jarvis")
        val whole = scoresPerChunk(WakeWordDetector(models), clip, WAKE_CHUNK_SAMPLES)
        val sliced = scoresPerChunk(WakeWordDetector(models), clip, 1000)
        assertArrayEquals(whole.toFloatArray(), sliced.toFloatArray(), 0f)
    }

    @Test
    fun `reset forgets everything, including a detection in progress`() {
        val detector = WakeWordDetector(models)
        val clip = readClip("hey_jarvis")
        scoresPerChunk(detector, clip, WAKE_CHUNK_SAMPLES)

        detector.reset()
        val afterReset = scoresPerChunk(detector, clip, WAKE_CHUNK_SAMPLES)
        val fresh = scoresPerChunk(WakeWordDetector(models), clip, WAKE_CHUNK_SAMPLES)
        assertArrayEquals(fresh.toFloatArray(), afterReset.toFloatArray(), 0f)
    }

    // ── streaming arithmetic, with fake models ───────────────────────────────────────

    private class RecordingModels : WakeWordModels {
        override val featureFrames = 3
        val melInputs = ArrayList<FloatArray>()
        val embedInputs = ArrayList<Array<FloatArray>>()
        val scoreInputs = ArrayList<Array<FloatArray>>()

        /** Emits one frame per 160 samples past the first 400, like the real model. */
        override fun melspectrogram(samples: FloatArray): Array<FloatArray> {
            melInputs += samples
            val frames = if (samples.size >= 1760) 8 else 5
            return Array(frames) { FloatArray(32) { melInputs.size.toFloat() * 10f } }
        }

        override fun embed(window: Array<FloatArray>): FloatArray {
            embedInputs += window
            return FloatArray(96) { embedInputs.size.toFloat() }
        }

        override fun score(features: Array<FloatArray>): Float {
            scoreInputs += features
            return 0.25f
        }

        override fun close() = Unit
    }

    @Test
    fun `nothing is scored until a whole chunk has arrived`() {
        val detector = WakeWordDetector(RecordingModels())
        assertNull(detector.accept(ShortArray(WAKE_CHUNK_SAMPLES - 1)))
        assertEquals(0f, detector.accept(ShortArray(1))!!, 0f)
    }

    @Test
    fun `the mel model gets raw sample values with 480 samples of context`() {
        val fake = RecordingModels()
        val detector = WakeWordDetector(fake)
        val audio = ShortArray(WAKE_CHUNK_SAMPLES * 3) { it.toShort() }
        detector.accept(audio)

        assertEquals(listOf(1280, 1760, 1760), fake.melInputs.map { it.size })
        // Unscaled int16 values, not normalised to ±1.
        assertEquals(1279f, fake.melInputs[0].last(), 0f)
        // The third chunk's input starts 480 samples before that chunk.
        assertEquals((WAKE_CHUNK_SAMPLES * 2 - 480).toFloat(), fake.melInputs[2].first(), 0f)
        assertEquals((WAKE_CHUNK_SAMPLES * 3 - 1).toFloat(), fake.melInputs[2].last(), 0f)
    }

    @Test
    fun `embedding windows are 76 transformed frames padded with ones`() {
        val fake = RecordingModels()
        val detector = WakeWordDetector(fake)
        detector.accept(ShortArray(WAKE_CHUNK_SAMPLES))

        val window = fake.embedInputs.single()
        assertEquals(76, window.size)
        // 71 frames of the initial padding, then the first chunk's 5 frames transformed by
        // x / 10 + 2: the fake emitted 10, so each value is 3.
        assertTrue(window.take(71).all { frame -> frame.all { it == 1f } })
        assertTrue(window.drop(71).all { frame -> frame.all { it == 3f } })
    }

    @Test
    fun `scores the most recent embeddings once enough exist`() {
        val fake = RecordingModels()
        val detector = WakeWordDetector(fake)
        val scores = (1..5).map { detector.accept(ShortArray(WAKE_CHUNK_SAMPLES))!! }

        assertEquals(listOf(0f, 0f, 0.25f, 0.25f, 0.25f), scores)
        // The last call scored embeddings 3, 4 and 5, oldest first.
        assertEquals(listOf(3f, 4f, 5f), fake.scoreInputs.last().map { it[0] })
    }

    @Test
    fun `several chunks in one read report the best score`() {
        val detector = WakeWordDetector(object : WakeWordModels by RecordingModels() {
            private var n = 0
            override val featureFrames = 1
            override fun score(features: Array<FloatArray>) = listOf(0.1f, 0.9f, 0.2f)[n++]
        })
        assertEquals(0.9f, detector.accept(ShortArray(WAKE_CHUNK_SAMPLES * 3))!!, 0f)
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────

    private fun scoresPerChunk(detector: WakeWordDetector, clip: ShortArray, readSize: Int): List<Float> {
        val scores = ArrayList<Float>()
        var offset = 0
        // Only whole chunks, as the Python reference scores only whole chunks.
        val usable = clip.size - clip.size % WAKE_CHUNK_SAMPLES
        while (offset < usable) {
            val n = minOf(readSize, usable - offset)
            detector.accept(clip, offset, n)?.let { scores += it }
            offset += n
        }
        return scores
    }

    /** Reads a 16 kHz mono PCM WAV. Unit tests compile against android.jar, so no javax.sound. */
    private fun readClip(name: String): ShortArray {
        val bytes = javaClass.classLoader!!.getResource("lexi/$name.wav")!!.readBytes()
        val wav = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        wav.position(12) // past "RIFF", the size and "WAVE"
        while (wav.remaining() >= 8) {
            val id = String(bytes, wav.position(), 4, Charsets.US_ASCII)
            wav.position(wav.position() + 4)
            val size = wav.int
            when (id) {
                "fmt " -> assertEquals(16_000, wav.getInt(wav.position() + 4))
                "data" -> {
                    val pcm = wav.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    return ShortArray(size / 2).also { pcm.get(it) }
                }
            }
            wav.position(wav.position() + size + size % 2)
        }
        error("$name.wav has no data chunk")
    }

    companion object {
        private lateinit var models: OnnxWakeWordModels
        private lateinit var golden: Map<String, List<Float>>

        @BeforeClass
        @JvmStatic
        fun loadModels() {
            val dir = File(
                System.getProperty("lexi.models")
                    ?: error("lexi.models is not set; run through Gradle so the models are downloaded"),
            )
            models = OnnxWakeWordModels(
                dir.resolve("melspectrogram.onnx").readBytes(),
                dir.resolve("embedding_model.onnx").readBytes(),
                dir.resolve("hey_jarvis_v0.1.onnx").readBytes(),
            )
            val text = WakeWordDetectorTest::class.java.classLoader!!
                .getResource("lexi/golden_scores.json")!!.readText()
            golden = Json.parseToJsonElement(text).jsonObject.mapValues { (_, scores) ->
                scores.jsonArray.map { it.jsonPrimitive.float }
            }
        }

        @AfterClass
        @JvmStatic
        fun closeModels() = models.close()
    }
}

package com.alexdyakin.lexicon.lexi

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lexi's engines on a real Android runtime, fed recorded clips instead of a microphone.
 *
 * The JVM tests prove the wake-word port is correct; these prove the pieces that only
 * exist on a device actually work there: the Android ONNX Runtime and Vosk native
 * libraries load, the models are packaged where the app looks for them, the speech model
 * unpacks, and the phone can see a text-to-speech engine (which, since API 30, depends on
 * a manifest declaration that is easy to forget).
 *
 * The clips were synthesized by Lexi's Piper voice on the Pi setup.
 *
 * The speech test downloads the model the first time it runs on a device, exactly as the
 * app does, so it needs a network on that first run.
 */
@RunWith(AndroidJUnit4::class)
class LexiOnDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val fixtures = instrumentation.context

    @Test
    fun wakeWordFiresOnDevice() {
        fun model(name: String) = app.assets.open("lexi/wakeword/$name").use { it.readBytes() }
        OnnxWakeWordModels(
            model("melspectrogram.onnx"),
            model("embedding_model.onnx"),
            model("hey_jarvis_v0.1.onnx"),
        ).use { models ->
            val detector = WakeWordDetector(models)
            val clip = clip("hey_jarvis")
            var peak = 0f
            for (offset in 0 until clip.size - clip.size % WAKE_CHUNK_SAMPLES step WAKE_CHUNK_SAMPLES) {
                detector.accept(clip, offset, WAKE_CHUNK_SAMPLES)?.let { peak = maxOf(peak, it) }
            }
            assertTrue("peak score was $peak", peak > 0.9f)
        }
    }

    @Test
    fun speechIsTranscribedOnDevice() = runBlocking {
        val modelDir = SpeechModelInstaller(
            http = OkHttpClient(),
            modelDir = app.filesDir.resolve("lexi/vosk"),
            workDir = app.cacheDir.resolve("lexi-speech-model"),
        ).ensureInstalled()

        val heard = Transcriber.load(modelDir).use { transcriber ->
            transcriber.begin().use { utterance ->
                // A microphone keeps delivering audio after the speaker stops; the clip does
                // not, so pad it with the silence a real one would hear.
                val clip = clip("what_time") + ShortArray(LEXI_SAMPLE_RATE * 3)
                var offset = 0
                var done = false
                while (!done && offset < clip.size) {
                    val n = minOf(WAKE_CHUNK_SAMPLES, clip.size - offset)
                    done = utterance.accept(clip.copyOfRange(offset, offset + n), n)
                    offset += n
                }
                val text = utterance.text()
                assertTrue("Vosk's endpointer should close the utterance (heard \"$text\" after ${offset / LEXI_SAMPLE_RATE.toFloat()} s)", done)
                text
            }
        }
        assertEquals("what time is it", heard)
    }

    @Test
    fun textToSpeechIsAvailable() = runBlocking {
        lateinit var speaker: Speaker
        instrumentation.runOnMainSync { speaker = Speaker(app) }
        try {
            assertTrue("no text-to-speech engine was visible to the app", speaker.awaitReady())
        } finally {
            speaker.close()
        }
    }

    /** Reads a 16 kHz mono PCM WAV from the test APK's assets. */
    private fun clip(name: String): ShortArray {
        val bytes = fixtures.assets.open("lexi/$name.wav").use { it.readBytes() }
        val wav = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        wav.position(12)
        while (wav.remaining() >= 8) {
            val id = String(bytes, wav.position(), 4, Charsets.US_ASCII)
            wav.position(wav.position() + 4)
            val size = wav.int
            if (id == "data") {
                val pcm = wav.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                return ShortArray(size / 2).also { pcm.get(it) }
            }
            wav.position(wav.position() + size + size % 2)
        }
        error("$name.wav has no data chunk")
    }
}

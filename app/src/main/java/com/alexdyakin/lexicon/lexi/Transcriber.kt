package com.alexdyakin.lexicon.lexi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * On-device speech recognition with Vosk.
 *
 * Vosk also decides when the speaker has finished, so there is no separate voice-activity
 * detector: its endpointer is set to the same timings the Pi uses (0.8 s of trailing
 * silence, 10 s at most), and gives up after 5 s if nobody starts talking.
 */
class Transcriber private constructor(private val model: Model) : AutoCloseable {

    /** Starts listening for one question. Feed it audio until [Utterance.accept] says done. */
    fun begin(): Utterance = Utterance(
        Recognizer(model, LEXI_SAMPLE_RATE.toFloat()).apply {
            setEndpointerDelays(NO_SPEECH_TIMEOUT_S, TRAILING_SILENCE_S, MAX_UTTERANCE_S)
        },
    )

    override fun close() = model.close()

    class Utterance internal constructor(private val recognizer: Recognizer) : AutoCloseable {
        private var samples = 0
        private var endpointed = false

        /** Feeds audio; true once the speaker has finished, or the hard cap is reached. */
        fun accept(audio: ShortArray, length: Int): Boolean {
            samples += length
            if (recognizer.acceptWaveForm(audio, length)) {
                endpointed = true
                return true
            }
            // The endpointer's own cap should end things first. This is the backstop, so a
            // misbehaving native endpointer can never hold the microphone open indefinitely.
            return samples >= HARD_CAP_SAMPLES
        }

        /**
         * The transcript, once [accept] has returned true. Vosk keeps an endpointed segment
         * in `Result`, while `FinalResult` is for flushing audio it has not endpointed yet,
         * so which one to read depends on how the utterance ended.
         */
        fun text(): String = parseText(if (endpointed) recognizer.result else recognizer.finalResult)

        override fun close() = recognizer.close()
    }

    companion object {
        private const val NO_SPEECH_TIMEOUT_S = 5f
        private const val TRAILING_SILENCE_S = 0.8f
        private const val MAX_UTTERANCE_S = 10f
        private const val HARD_CAP_SAMPLES = LEXI_SAMPLE_RATE * 12

        private val json = Json { ignoreUnknownKeys = true }

        internal fun parseText(result: String): String =
            runCatching {
                (json.parseToJsonElement(result).jsonObject["text"] as? JsonPrimitive)?.contentOrNull
            }.getOrNull().orEmpty().trim()

        /**
         * Loads a model directory prepared by [SpeechModelInstaller]. Takes a second or two,
         * so it is kept off the main thread.
         */
        suspend fun load(modelDir: File): Transcriber = withContext(Dispatchers.IO) {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            Transcriber(Model(modelDir.absolutePath))
        }
    }
}

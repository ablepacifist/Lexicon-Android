package com.alexdyakin.lexicon.lexi

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * Lexi's voice: Android's own text-to-speech, which works offline with the voices already
 * on the phone and follows the audio route, so answers land in headphones when they are in.
 */
class Speaker(context: Context) : AutoCloseable {

    private val ready = CompletableDeferred<Boolean>()
    private val tts = TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }

    /**
     * Utterances queued but not yet finished, by id. Ids rather than a count, so a late
     * callback from an utterance that [stop] already discarded cannot cut short the wait
     * for the next turn's speech.
     */
    private val pending = MutableStateFlow<Set<String>>(emptySet())
    private val ids = AtomicLong()

    init {
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finished(utteranceId)

            @Deprecated("Required override on older API levels")
            override fun onError(utteranceId: String?) = finished(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = finished(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)
        })
    }

    private fun finished(id: String?) {
        pending.update { it - id.orEmpty() }
    }

    /** False if the phone has no usable text-to-speech engine. */
    suspend fun awaitReady(): Boolean = withTimeoutOrNull(READY_TIMEOUT_MS) { ready.await() } ?: false

    /** Queues [text] behind anything already being spoken. */
    fun speak(text: String) {
        val id = "lexi-${ids.incrementAndGet()}"
        pending.update { it + id }
        if (tts.speak(text, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS) finished(id)
    }

    /** Suspends until everything queued has been spoken, or stopped. */
    suspend fun awaitSilence() {
        pending.first { it.isEmpty() }
    }

    /** Stops talking immediately and drops the queue. */
    fun stop() {
        tts.stop()
        pending.value = emptySet()
    }

    override fun close() {
        stop()
        tts.shutdown()
    }

    private companion object {
        const val READY_TIMEOUT_MS = 10_000L
    }
}

/**
 * The short tones that tell a user, without looking, what Lexi is doing: one when it starts
 * listening for a question, another when it gave up without hearing one.
 */
class Cues : AutoCloseable {
    private val tones = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, VOLUME) }.getOrNull()

    fun listening() {
        tones?.startTone(ToneGenerator.TONE_PROP_BEEP2, 180)
    }

    fun nothingHeard() {
        tones?.startTone(ToneGenerator.TONE_PROP_NACK, 220)
    }

    override fun close() {
        tones?.release()
    }

    private companion object {
        const val VOLUME = 70
    }
}

/**
 * Holds transient audio focus for one turn, so the user's music pauses while Lexi listens
 * and answers, then resumes. Media3 in [com.alexdyakin.lexicon.media.PlaybackService]
 * already handles focus, so nothing there needs to know Lexi exists.
 */
class TurnFocus(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val listener = AudioManager.OnAudioFocusChangeListener { }
    private var request: AudioFocusRequest? = null

    fun acquire() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setOnAudioFocusChangeListener(listener)
                .build()
            request = focus
            audio?.requestAudioFocus(focus)
        } else {
            @Suppress("DEPRECATION")
            audio?.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
    }

    fun release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            request?.let { audio?.abandonAudioFocusRequest(it) }
            request = null
        } else {
            @Suppress("DEPRECATION")
            audio?.abandonAudioFocus(listener)
        }
    }
}

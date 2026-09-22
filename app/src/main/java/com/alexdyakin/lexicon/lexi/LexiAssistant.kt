package com.alexdyakin.lexicon.lexi

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.alexdyakin.lexicon.data.TokenStore
import com.alexdyakin.lexicon.voice.VoiceSession
import com.alexdyakin.lexicon.voice.VoiceState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/** What Lexi is doing, for the screen and the notification. */
sealed interface LexiState {
    data object Off : LexiState
    data class Starting(val detail: String) : LexiState
    /** Waiting for the wake word. */
    data object Listening : LexiState
    /** Recording the question. */
    data object Capturing : LexiState
    data class Thinking(val heard: String) : LexiState
    data class Speaking(val heard: String) : LexiState
    /** The microphone is lent to a Mumble voice call. */
    data object PausedForCall : LexiState
    data class Failed(val reason: String) : LexiState
}

/** One exchange, kept in memory for the screen. Never written to disk. */
data class LexiTurn(val heard: String, val reply: String, val failed: Boolean, val atMillis: Long)

/**
 * The hands-free loop: wake word, question, answer, spoken reply. The phone counterpart of
 * `pipeline.py` on the Pi, with the same shape and the same rules:
 *
 *  - **One microphone handle.** A single [AudioRecord] feeds both the wake word and the
 *    question, and is never opened twice, the rule that bit the Pi's USB microphone.
 *  - **It never listens to itself.** While thinking or speaking, audio is read and thrown
 *    away rather than scored, so Lexi's own answer cannot wake it. That is also why the
 *    capture source is `VOICE_RECOGNITION` rather than `VOICE_COMMUNICATION`: echo
 *    cancellation buys nothing when nothing listens during playback, and the call-tuned
 *    source adds gain and noise processing the wake-word model was not trained on.
 *  - **Only text leaves the phone.** Audio stays in this process; the transcript goes to
 *    the brain.
 *  - **The wake word is forgotten after every turn**, so the end of a question cannot
 *    immediately trigger another.
 *
 * Owned by [LexiService], which keeps the process allowed to use the microphone.
 */
@Singleton
class LexiAssistant @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: LexiSettingsStore,
    private val tokenStore: TokenStore,
    private val brain: BrainClient,
    private val speechModels: SpeechModelInstaller,
    private val voiceCall: VoiceSession,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var session: Job? = null

    private val _state = MutableStateFlow<LexiState>(LexiState.Off)
    val state: StateFlow<LexiState> = _state.asStateFlow()

    /** The latest wake-word score while listening, for tuning sensitivity by eye. */
    private val _wakeScore = MutableStateFlow(0f)
    val wakeScore: StateFlow<Float> = _wakeScore.asStateFlow()

    private val _turns = MutableStateFlow<List<LexiTurn>>(emptyList())
    val turns: StateFlow<List<LexiTurn>> = _turns.asStateFlow()

    private enum class Phase { WAKE, QUESTION, BUSY }

    /** Who owns the microphone's audio right now. Moved only by compare-and-set. */
    private val phase = AtomicReference(Phase.WAKE)
    private val askRequested = AtomicBoolean(false)
    private val cancelRequested = AtomicBoolean(false)
    private val wakeResetRequested = AtomicBoolean(false)

    /** Where turns run while listening; null when not listening. */
    @Volatile private var turnScope: CoroutineScope? = null
    @Volatile private var turnJob: Job? = null

    /** The conversation continues across turns, as on the Pi, until Lexi is switched off. */
    @Volatile private var chatId: String? = null

    fun start() {
        if (session?.isActive == true) return
        chatId = null
        // Set before launching, so an observer never sees a stale Off or Failed from the
        // previous session and concludes this one has already ended.
        _state.value = LexiState.Starting("Starting…")
        session = scope.launch { runSession() }
    }

    fun stop() {
        session?.cancel()
        session = null
        _state.value = LexiState.Off
        _wakeScore.value = 0f
    }

    /** Skip the wake word and listen for a question now. */
    fun askNow() {
        if (phase.get() == Phase.WAKE) askRequested.set(true)
    }

    /** Ask by typing: the same turn, minus the microphone. */
    fun askText(text: String) {
        val turns = turnScope ?: return
        val loaded = engines ?: return
        if (text.isBlank() || !phase.compareAndSet(Phase.WAKE, Phase.BUSY)) return
        loaded.focus.acquire()
        turnJob = turns.launch { runTurn(text.trim(), loaded) }
    }

    /** Abandon the question or answer in progress and go back to waiting for the wake word. */
    fun cancelTurn() {
        cancelRequested.set(true)
        turnJob?.cancel()
    }

    // ── session ──────────────────────────────────────────────────────────────────────

    @Volatile private var engines: Engines? = null

    private class Engines(
        val models: OnnxWakeWordModels,
        val transcriber: Transcriber,
        val speaker: Speaker,
        val cues: Cues,
        val focus: TurnFocus,
    ) : AutoCloseable {
        val wake = WakeWordDetector(models)

        override fun close() {
            speaker.close()
            cues.close()
            transcriber.close()
            models.close()
        }
    }

    private suspend fun runSession() {
        if (settingsStore.agentToken() == null) {
            return fail("Add the brain's agent token below first.")
        }
        if (!hasMicPermission()) {
            return fail("Lexi needs the microphone permission.")
        }

        val loaded = try {
            loadEngines()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not load engines", e)
            return fail(e.message ?: "Lexi's models could not be loaded.")
        }
        engines = loaded

        try {
            // A Mumble call needs the microphone too. Lexi steps aside for the call's
            // duration and picks up again when it ends, rather than both fighting over it.
            voiceCall.state
                .map { it is VoiceState.Connecting || it is VoiceState.Active }
                .distinctUntilChanged()
                .collectLatest { inCall ->
                    if (inCall) {
                        _state.value = LexiState.PausedForCall
                        awaitCancellation()
                    } else {
                        listen(loaded)
                    }
                }
        } catch (e: MicrophoneLost) {
            fail(e.message!!)
        } finally {
            engines = null
            withContext(NonCancellable) { loaded.close() }
        }
    }

    private suspend fun loadEngines(): Engines {
        // First switch-on fetches the speech model; afterwards this is a no-op.
        val modelDir = speechModels.ensureInstalled { fraction ->
            _state.value = LexiState.Starting(
                if (fraction == null) "Downloading the speech model…"
                else "Downloading the speech model… ${(fraction * 100).toInt()}%",
            )
        }
        _state.value = LexiState.Starting("Preparing speech recognition…")
        val transcriber = Transcriber.load(modelDir)
        try {
            _state.value = LexiState.Starting("Loading the wake word…")
            val models = withContext(Dispatchers.IO) {
                fun model(name: String) = context.assets.open("lexi/wakeword/$name").use { it.readBytes() }
                OnnxWakeWordModels(
                    model("melspectrogram.onnx"),
                    model("embedding_model.onnx"),
                    model("hey_jarvis_v0.1.onnx"),
                )
            }
            try {
                _state.value = LexiState.Starting("Warming up the voice…")
                val speaker = withContext(Dispatchers.Main) { Speaker(context) }
                if (!speaker.awaitReady()) {
                    speaker.close()
                    error("This phone has no working text-to-speech voice.")
                }
                return Engines(models, transcriber, speaker, Cues(), TurnFocus(context))
            } catch (e: Throwable) {
                models.close()
                throw e
            }
        } catch (e: Throwable) {
            transcriber.close()
            throw e
        }
    }

    // ── the microphone loop ──────────────────────────────────────────────────────────

    private class MicrophoneLost(message: String) : Exception(message)

    private suspend fun listen(engines: Engines) = coroutineScope {
        turnScope = this
        try {
            withContext(Dispatchers.IO) { readMicrophone(engines, turns = this@coroutineScope) }
        } finally {
            turnScope = null
            phase.set(Phase.WAKE)
            engines.speaker.stop()
            engines.focus.release()
        }
    }

    private suspend fun readMicrophone(engines: Engines, turns: CoroutineScope) {
        val record = openMicrophone() ?: throw MicrophoneLost("The microphone is unavailable.")
        var question: Transcriber.Utterance? = null
        try {
            record.startRecording()
            backToWake(engines)
            val buffer = ShortArray(WAKE_CHUNK_SAMPLES)

            while (currentCoroutineContext().isActive) {
                val read = record.read(buffer, 0, buffer.size)
                if (read < 0) throw MicrophoneLost("The microphone stopped working ($read).")
                if (read == 0) continue

                if (wakeResetRequested.getAndSet(false)) engines.wake.reset()

                when (phase.get()) {
                    Phase.WAKE -> {
                        cancelRequested.set(false)
                        val asked = askRequested.getAndSet(false)
                        val score = if (asked) null else engines.wake.accept(buffer, 0, read)
                        if (score != null) _wakeScore.value = score
                        val woken = asked || (score != null && score >= settingsStore.settings.value.wakeThreshold)
                        if (woken && phase.compareAndSet(Phase.WAKE, Phase.QUESTION)) {
                            _wakeScore.value = 0f
                            _state.value = LexiState.Capturing
                            engines.focus.acquire()
                            engines.cues.listening()
                            question = engines.transcriber.begin()
                        }
                    }

                    Phase.QUESTION -> {
                        val utterance = question ?: continue
                        val cancelled = cancelRequested.getAndSet(false)
                        if (cancelled || utterance.accept(buffer, read)) {
                            val heard = if (cancelled) "" else utterance.text()
                            utterance.close()
                            question = null
                            if (heard.isEmpty()) {
                                if (!cancelled) engines.cues.nothingHeard()
                                engines.focus.release()
                                backToWake(engines)
                            } else if (phase.compareAndSet(Phase.QUESTION, Phase.BUSY)) {
                                turnJob = turns.launch { runTurn(heard, engines) }
                            }
                        }
                    }

                    // Thinking or speaking: drain the microphone so stale audio does not
                    // queue up, and never score it, so Lexi cannot wake itself.
                    Phase.BUSY -> Unit
                }
            }
        } finally {
            question?.close()
            runCatching { record.stop() }
            record.release()
        }
    }

    private fun backToWake(engines: Engines) {
        wakeResetRequested.set(true)
        phase.set(Phase.WAKE)
        _state.value = LexiState.Listening
    }

    // ── a turn ───────────────────────────────────────────────────────────────────────

    private suspend fun runTurn(heard: String, engines: Engines) {
        _state.value = LexiState.Thinking(heard)
        var reply = ""
        var failed = false
        var spokeAnything = false

        fun say(sentence: String) {
            val spoken = stripMarkdown(sentence)
            if (spoken.isEmpty()) return
            spokeAnything = true
            _state.value = LexiState.Speaking(heard)
            engines.speaker.speak(spoken)
        }

        try {
            try {
                val token = settingsStore.agentToken() ?: throw MissingToken()
                val settings = settingsStore.settings.value
                val chunker = SentenceChunker()
                val result = cancellableCall { register ->
                    brain.streamTurn(
                        request = BrainRequest(settings.bases, token, settings.orchestrator),
                        message = heard,
                        accountId = accountId(),
                        chatId = chatId,
                        onCall = register,
                    ) { token -> chunker.feed(token).forEach(::say) }
                }
                chunker.flush()?.let(::say)
                chatId = result.chatId ?: chatId
                reply = result.text
                if (!spokeAnything) {
                    reply = NO_ANSWER
                    say(reply)
                }
            } catch (e: IOException) {
                Log.w(TAG, "Turn failed: ${e.javaClass.simpleName}")
                failed = true
                reply = spokenFailure(e)
                say(reply)
            }
            engines.speaker.awaitSilence()
            _turns.value = (listOf(LexiTurn(heard, reply, failed, System.currentTimeMillis())) + _turns.value)
                .take(MAX_TURNS)
        } catch (e: CancellationException) {
            engines.speaker.stop()
            throw e
        } finally {
            engines.focus.release()
            if (turnScope?.isActive == true) backToWake(engines) else phase.set(Phase.WAKE)
            turnJob = null
        }
    }

    /**
     * Runs a blocking brain call so that cancelling the coroutine really stops it.
     *
     * A socket read ignores coroutine cancellation, so without this a cancelled turn would
     * sit waiting for the brain to finish talking. Cancelling the HTTP call unblocks it.
     */
    private suspend fun <T> cancellableCall(block: (register: (Call) -> Unit) -> T): T = coroutineScope {
        val current = AtomicReference<Call?>()
        val work = async(Dispatchers.IO) { block { current.set(it) } }
        try {
            work.await()
        } catch (e: CancellationException) {
            current.get()?.cancel()
            throw e
        }
    }

    private fun accountId(): String =
        tokenStore.userId.takeIf { it > 0 }?.toString() ?: DEFAULT_ACCOUNT_ID

    // ── helpers ──────────────────────────────────────────────────────────────────────

    private fun fail(reason: String) {
        _state.value = LexiState.Failed(reason)
        _wakeScore.value = 0f
    }

    private fun hasMicPermission() = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // Checked in runSession before the loop starts.
    private fun openMicrophone(): AudioRecord? {
        val minBuffer = AudioRecord.getMinBufferSize(
            LEXI_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return null
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            LEXI_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            // Room for several chunks, so a slow model run does not overrun the hardware buffer.
            maxOf(minBuffer, WAKE_CHUNK_SAMPLES * 2 * 4),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }
        return record
    }

    private companion object {
        const val TAG = "Lexi"
        const val MAX_TURNS = 20
        /** The Pi's fallback bucket when no Lexicon user is known. */
        const val DEFAULT_ACCOUNT_ID = "local-default"
        const val NO_ANSWER = "I didn't get an answer back."
    }
}

internal class MissingToken : IOException("No agent token")

/**
 * What Lexi says when a turn cannot be answered. Spoken, so short and plain, and specific
 * enough that the user knows whether to wait (brain off) or fix something (token).
 */
internal fun spokenFailure(e: Throwable): String = when (e) {
    is BrainUnreachable -> "The brain's offline right now."
    is BrainAuthError -> "The brain didn't accept my token. Check Lexi's settings."
    is MissingToken -> "I don't have a token for the brain yet. Add one in Lexi's settings."
    is BrainError -> "Something went wrong on the brain's side."
    else -> "I couldn't reach the brain."
}

package com.alexdyakin.lexicon.voice

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import com.alexdyakin.lexicon.data.bridge.BridgeConnection
import com.alexdyakin.lexicon.data.bridge.JitterBuffer
import com.alexdyakin.lexicon.data.bridge.PcmFramer
import com.alexdyakin.lexicon.data.bridge.SAMPLES_PER_FRAME
import com.alexdyakin.lexicon.data.bridge.SAMPLE_RATE
import com.alexdyakin.lexicon.data.bridge.VoiceReadyEvent
import com.alexdyakin.lexicon.data.bridge.VoiceStartCommand
import com.alexdyakin.lexicon.data.bridge.VoiceStopCommand
import com.alexdyakin.lexicon.data.bridge.VoiceStoppedEvent
import com.alexdyakin.lexicon.data.bridge.mix
import com.alexdyakin.lexicon.data.bridge.parseVoicePacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString
import okio.ByteString.Companion.toByteString
import javax.inject.Inject
import javax.inject.Singleton

/** What the voice UI needs to know. */
sealed interface VoiceState {
    data object Idle : VoiceState
    /** Waiting for the server to open its own Mumble connection. */
    data object Connecting : VoiceState
    data class Active(val channelId: Int?, val muted: Boolean, val speakers: Set<Long>) : VoiceState
    data class Failed(val reason: String) : VoiceState
}

/**
 * Two-way audio over the bridge socket.
 *
 * No codec work happens here. The bridge runs Opus on its side and the socket carries
 * raw 48 kHz mono PCM, so this owns three things: capture framed to exactly what the
 * server will accept, playback mixed from however many people are talking, and the
 * handshake between them.
 *
 * The failure modes worth knowing about are all quiet ones:
 *
 *  - **Binary sent before `voice_ready` is discarded**, so capture cannot start until
 *    the frame arrives. It can take many seconds, because the server opens a fresh TLS
 *    connection to Mumble with retries, so the wait is generous rather than snappy.
 *  - **The server discards a partial frame** instead of buffering it, which [PcmFramer]
 *    exists to prevent.
 *  - **The server does not mix.** Each speaker arrives separately keyed by Mumble
 *    session id, and summing them without clamping wraps into audible cracks.
 *  - **Audio ids and presence ids are different namespaces.** The prefix here is a
 *    Mumble session number; `voice_state` uses `web_<userId>`. Nothing maps between
 *    them, so speakers are reported as raw session ids for the caller to resolve.
 */
@Singleton
class VoiceSession @Inject constructor(
    private val connection: BridgeConnection,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private var captureJob: Job? = null
    private var playbackJob: Job? = null
    private var downlinkJob: Job? = null

    private var record: AudioRecord? = null
    private var track: AudioTrack? = null

    /** One buffer per speaker, keyed by Mumble session id. Guarded by [buffersLock]. */
    private val buffers = HashMap<Long, JitterBuffer>()
    private val buffersLock = Any()

    @Volatile
    private var muted = false

    /**
     * Opens a session and starts audio once the server says it is ready.
     *
     * [channelId] is optional — omitted, the bridge leaves the session wherever Mumble
     * puts it by default.
     */
    fun start(channelId: Int? = null) {
        if (_state.value !is VoiceState.Idle && _state.value !is VoiceState.Failed) return
        _state.value = VoiceState.Connecting

        scope.launch {
            connection.send(VoiceStartCommand(channelId))

            // Generous, because the server dials Mumble over TLS with retries before it
            // answers. Treating a slow reply as a failure would abandon a session that
            // was about to work.
            val ready = withTimeoutOrNull(VOICE_READY_TIMEOUT_MS) {
                connection.events.filterIsInstance<VoiceReadyEvent>().first()
            }
            if (ready == null) {
                _state.value = VoiceState.Failed("Voice did not start")
                return@launch
            }

            _state.value = VoiceState.Active(channelId, muted, emptySet())
            startDownlink()
            startCapture()
            startPlayback()
        }
    }

    fun stop() {
        captureJob?.cancel(); captureJob = null
        playbackJob?.cancel(); playbackJob = null
        downlinkJob?.cancel(); downlinkJob = null

        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null

        synchronized(buffersLock) { buffers.clear() }
        _state.value = VoiceState.Idle
        scope.launch { runCatching { connection.send(VoiceStopCommand()) } }
    }

    fun setMuted(value: Boolean) {
        muted = value
        (_state.value as? VoiceState.Active)?.let { _state.value = it.copy(muted = value) }
    }

    // ── downlink ─────────────────────────────────────────────────────────────

    private fun startDownlink() {
        downlinkJob = scope.launch {
            connection.audio.collect { frame -> onAudioFrame(frame) }
        }
        scope.launch {
            connection.events.filterIsInstance<VoiceStoppedEvent>().first()
            stop()
        }
    }

    private fun onAudioFrame(frame: ByteString) {
        val packet = parseVoicePacket(frame.toByteArray()) ?: return
        synchronized(buffersLock) {
            buffers.getOrPut(packet.session) { JitterBuffer() }.push(packet.pcm)
        }
    }

    // ── uplink ───────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission") // The screen requires RECORD_AUDIO before starting.
    private fun startCapture() {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            _state.value = VoiceState.Failed("Microphone unavailable")
            return
        }

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            // Four frames of headroom so a scheduling hiccup does not overrun the
            // hardware buffer; the minimum is frequently smaller than one frame.
            maxOf(minBuffer, BYTES_PER_FRAME_LOCAL * 4),
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            _state.value = VoiceState.Failed("Microphone unavailable")
            return
        }
        record = recorder
        recorder.startRecording()

        captureJob = scope.launch {
            // The read size deliberately is not a frame multiple; the framer exists
            // precisely so it does not have to be.
            val scratch = ByteArray(maxOf(minBuffer, BYTES_PER_FRAME_LOCAL))
            val framer = PcmFramer()
            while (true) {
                val read = recorder.read(scratch, 0, scratch.size)
                if (read <= 0) continue
                if (muted) {
                    // Still drained, so unmuting does not replay a backlog of stale
                    // audio, but nothing is held or sent.
                    framer.reset()
                    continue
                }
                framer.offer(scratch, read).forEach { frame ->
                    if (!connection.sendAudio(frame.toByteString())) {
                        Log.w(TAG, "audio frame dropped; socket not writable")
                    }
                }
            }
        }
    }

    // ── playback ─────────────────────────────────────────────────────────────

    private fun startPlayback() {
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val player = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer, BYTES_PER_FRAME_LOCAL * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = player
        player.play()

        playbackJob = scope.launch {
            while (true) {
                val (frame, speakers) = nextMixedFrame()
                if (frame == null) {
                    // Nobody is speaking. AudioTrack.write blocks on a full buffer, so
                    // without a pause here this would spin a core flat out.
                    kotlinx.coroutines.delay(FRAME_MS)
                    continue
                }
                player.write(frame, 0, frame.size, AudioTrack.WRITE_BLOCKING)
                (_state.value as? VoiceState.Active)?.let {
                    if (it.speakers != speakers) _state.value = it.copy(speakers = speakers)
                }
            }
        }
    }

    /**
     * Mixes one frame from whoever is currently audible.
     *
     * A speaker that has run dry is dropped so their silence stops being mixed in, and
     * so a session id that never returns cannot accumulate buffers forever.
     */
    private fun nextMixedFrame(): Pair<ShortArray?, Set<Long>> {
        val ready = ArrayList<ShortArray>()
        val speaking = HashSet<Long>()

        synchronized(buffersLock) {
            val finished = ArrayList<Long>()
            for ((session, buffer) in buffers) {
                val chunk = buffer.drain(SAMPLES_PER_FRAME)
                if (chunk != null) {
                    ready += chunk
                    speaking += session
                }
                if (buffer.isDry()) finished += session
            }
            finished.forEach { buffers.remove(it) }
        }

        return if (ready.isEmpty()) null to emptySet() else mix(ready) to speaking
    }

    private companion object {
        const val TAG = "VoiceSession"
        const val VOICE_READY_TIMEOUT_MS = 15_000L
        const val FRAME_MS = 20L
        const val BYTES_PER_FRAME_LOCAL = SAMPLES_PER_FRAME * 2
    }
}

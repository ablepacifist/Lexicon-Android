package com.alexdyakin.lexicon.lexi

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.nio.FloatBuffer

/** Lexi listens at 16 kHz mono, like the Pi. Every model here assumes it. */
const val LEXI_SAMPLE_RATE = 16_000

/** openWakeWord scores one 80 ms chunk at a time. */
const val WAKE_CHUNK_SAMPLES = 1_280

/** The mel model sees three extra hops of context before each chunk. */
private const val MEL_CONTEXT_SAMPLES = WAKE_CHUNK_SAMPLES + 160 * 3
private const val MEL_BINS = 32
private const val EMBEDDING_WINDOW = 76

/**
 * openWakeWord's three networks: audio to mel spectrogram, mel windows to speech
 * embeddings, embeddings to a wake-word score.
 *
 * Behind an interface so [WakeWordDetector]'s streaming arithmetic, the part that was
 * hand-ported and so the part that can be wrong, is tested independently of the models.
 */
interface WakeWordModels : AutoCloseable {
    /** How many embeddings the wake-word model scores at once (16 for `hey_jarvis`). */
    val featureFrames: Int

    /** Raw sample values, unscaled, to mel frames of [MEL_BINS] each, as the model emits them. */
    fun melspectrogram(samples: FloatArray): Array<FloatArray>

    /** [EMBEDDING_WINDOW] mel frames to one embedding. */
    fun embed(window: Array<FloatArray>): FloatArray

    /** [featureFrames] embeddings to a score between 0 and 1. */
    fun score(features: Array<FloatArray>): Float
}

/**
 * Streaming wake-word detection, ported from openWakeWord's `AudioFeatures` and
 * `Model.predict` (0.6.0), which is what Lexi runs on the Pi.
 *
 * Details that matter and are easy to get wrong:
 *
 *  - The mel model takes int16 sample *values* as floats, not samples scaled to ±1.
 *  - Its output is transformed with `x / 10 + 2` before use.
 *  - Each chunk is analysed with the previous 480 samples of context, which is what
 *    yields exactly 8 new mel frames per chunk (only 5 for the very first chunk).
 *  - The mel buffer starts as 76 frames of ones, so the first embeddings are computed
 *    partly over that padding, exactly as upstream does.
 *
 * One deliberate difference: upstream fills its embedding buffer with embeddings of
 * random noise and zeroes only its first 5 scores, so scores 5 to 15 still depend on
 * that noise. Here nothing is reported until [featureFrames] real embeddings exist,
 * about 1.3 s after start or reset, so a detection can never come from padding.
 *
 * Not thread-safe; feed it from the one thread that reads the microphone.
 */
class WakeWordDetector(private val models: WakeWordModels) {

    private val pending = FloatArray(WAKE_CHUNK_SAMPLES)
    private var pendingCount = 0

    /** The most recent samples, oldest first, for the mel model's context. */
    private val context = FloatArray(MEL_CONTEXT_SAMPLES)
    private var contextCount = 0

    private val mel = ArrayDeque<FloatArray>()
    private val features = ArrayDeque<FloatArray>()
    private var realFeatures = 0

    init {
        reset()
    }

    /**
     * Feeds audio of any length. Returns the highest score among the chunks this call
     * completed, 0 while still warming up, or null if no chunk completed.
     */
    fun accept(samples: ShortArray, offset: Int = 0, length: Int = samples.size - offset): Float? {
        var best: Float? = null
        var index = offset
        val end = offset + length
        while (index < end) {
            val take = minOf(WAKE_CHUNK_SAMPLES - pendingCount, end - index)
            for (i in 0 until take) pending[pendingCount + i] = samples[index + i].toFloat()
            pendingCount += take
            index += take
            if (pendingCount == WAKE_CHUNK_SAMPLES) {
                val score = processChunk()
                pendingCount = 0
                best = if (best == null) score else maxOf(best, score)
            }
        }
        return best
    }

    /** Clears all history, so audio from before (such as the tail of a question) cannot fire it. */
    fun reset() {
        pendingCount = 0
        contextCount = 0
        mel.clear()
        repeat(EMBEDDING_WINDOW) { mel.addLast(FloatArray(MEL_BINS) { 1f }) }
        features.clear()
        realFeatures = 0
    }

    private fun processChunk(): Float {
        // Slide the context window left and append the chunk.
        if (contextCount + WAKE_CHUNK_SAMPLES > MEL_CONTEXT_SAMPLES) {
            val keep = MEL_CONTEXT_SAMPLES - WAKE_CHUNK_SAMPLES
            System.arraycopy(context, contextCount - keep, context, 0, keep)
            contextCount = keep
        }
        System.arraycopy(pending, 0, context, contextCount, WAKE_CHUNK_SAMPLES)
        contextCount += WAKE_CHUNK_SAMPLES

        for (frame in models.melspectrogram(context.copyOf(contextCount))) {
            mel.addLast(FloatArray(MEL_BINS) { frame[it] / 10f + 2f })
            if (mel.size > EMBEDDING_WINDOW) mel.removeFirst()
        }

        features.addLast(models.embed(mel.toTypedArray()))
        if (features.size > models.featureFrames) features.removeFirst()
        realFeatures++

        if (realFeatures < models.featureFrames) return 0f
        return models.score(features.toTypedArray())
    }
}

/**
 * [WakeWordModels] on ONNX Runtime, from the same `.onnx` files the Pi uses.
 *
 * Each session is pinned to one thread: three tiny models every 80 ms do not benefit from
 * more, and an always-on listener should not wake extra cores to run them.
 */
class OnnxWakeWordModels(
    melspectrogram: ByteArray,
    embedding: ByteArray,
    wakeWord: ByteArray,
) : WakeWordModels {

    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(1)
        setInterOpNumThreads(1)
    }
    private val melSession = env.createSession(melspectrogram, options)
    private val embeddingSession = env.createSession(embedding, options)
    private val wakeSession = env.createSession(wakeWord, options)

    override val featureFrames: Int =
        (wakeSession.inputInfo.values.first().info as TensorInfo).shape[1].toInt()

    override fun melspectrogram(samples: FloatArray): Array<FloatArray> =
        run(melSession, samples, longArrayOf(1, samples.size.toLong())) { output ->
            // (1, 1, frames, 32)
            @Suppress("UNCHECKED_CAST")
            (output as Array<Array<Array<FloatArray>>>)[0][0]
        }

    override fun embed(window: Array<FloatArray>): FloatArray {
        val flat = FloatArray(window.size * MEL_BINS)
        window.forEachIndexed { i, frame -> System.arraycopy(frame, 0, flat, i * MEL_BINS, MEL_BINS) }
        return run(embeddingSession, flat, longArrayOf(1, window.size.toLong(), MEL_BINS.toLong(), 1)) { output ->
            // (1, 1, 1, 96)
            @Suppress("UNCHECKED_CAST")
            (output as Array<Array<Array<FloatArray>>>)[0][0][0]
        }
    }

    override fun score(features: Array<FloatArray>): Float {
        val dim = features[0].size
        val flat = FloatArray(features.size * dim)
        features.forEachIndexed { i, f -> System.arraycopy(f, 0, flat, i * dim, dim) }
        return run(wakeSession, flat, longArrayOf(1, features.size.toLong(), dim.toLong())) { output ->
            // (1, 1)
            @Suppress("UNCHECKED_CAST")
            (output as Array<FloatArray>)[0][0]
        }
    }

    private inline fun <T> run(
        session: OrtSession,
        data: FloatArray,
        shape: LongArray,
        read: (Any) -> T,
    ): T = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { input ->
        session.run(mapOf(session.inputNames.first() to input)).use { result -> read(result[0].value) }
    }

    override fun close() {
        melSession.close()
        embeddingSession.close()
        wakeSession.close()
        options.close()
    }
}

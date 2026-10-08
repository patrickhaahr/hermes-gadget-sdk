package io.github.adolanium.hermesgadget

import java.io.Closeable

/** One TensorFlow Lite model with a float32 input and output. */
interface WakeModel : Closeable {
    /** Fixes the input shape and allocates the tensors. */
    fun resizeInput(shape: IntArray)

    val inputShape: IntArray
    val outputSize: Int

    fun run(input: FloatArray, output: FloatArray)
}

/** Opens the three models of the wake pipeline; assets/wake/ in the app. */
fun interface WakeModels {
    fun open(name: String): WakeModel
}

/**
 * "Hey Hermes" from 16 kHz mono PCM16, entirely on the phone: openWakeWord's
 * melspectrogram and embedding models feed the hey_hermes classifier. This is
 * pyopen-wakeword's streaming pipeline (the one Hermes Agent's desktop wake word
 * runs), window for window, with a detection rule on top: a score of at least
 * [threshold] in [confirmations] consecutive chunks.
 *
 * Not thread-safe: one thread feeds it.
 */
class WakeDetector(
    models: WakeModels,
    val threshold: Float = DEFAULT_THRESHOLD,
    private val confirmations: Int = DEFAULT_CONFIRMATIONS,
) : Closeable {
    private val mel = models.open(MEL_MODEL).apply { resizeInput(intArrayOf(1, MEL_SAMPLES)) }
    private val embedding = models.open(EMBEDDING_MODEL).apply { resizeInput(intArrayOf(1, EMB_FEATURES, NUM_MELS, 1)) }
    private val wakeword = models.open(WAKEWORD_MODEL)
    private val windows = wakeword.inputShape[1]

    private val audio = FloatArray(MAX_SECONDS * SAMPLE_RATE)
    private val mels = FloatArray(MAX_SECONDS * MELS_PER_SECOND * NUM_MELS)
    private val embeddings = FloatArray(MAX_SECONDS * EMB_STEP * WW_FEATURES)
    private var newSamples = 0
    private var newMels = 0
    private var newEmbeddings = 0
    private var streak = 0

    private val melIn = FloatArray(MEL_SAMPLES)
    private val melOut = FloatArray(mel.outputSize)
    private val embIn = FloatArray(EMB_FEATURES * NUM_MELS)
    private val embOut = FloatArray(embedding.outputSize)
    private val wwIn = FloatArray(windows * WW_FEATURES)
    private val wwOut = FloatArray(wakeword.outputSize)

    /** The highest score of the last [process] call, or NaN when it completed no window. */
    var lastScore = Float.NaN
        private set

    init {
        require(melOut.size % NUM_MELS == 0 && embOut.size == WW_FEATURES && wwOut.size == 1) { "unexpected wake model shapes" }
        reset()
    }

    /** Feeds one chunk (normally [CHUNK_SAMPLES]); true when the phrase is confirmed. */
    fun process(chunk: ShortArray): Boolean {
        lastScore = Float.NaN
        shiftIn(audio, chunk.size) { i -> chunk[i].toFloat() }
        newSamples = minOf(audio.size, newSamples + chunk.size)
        while (newSamples >= MEL_SAMPLES) {
            System.arraycopy(audio, audio.size - newSamples, melIn, 0, MEL_SAMPLES)
            newSamples = maxOf(0, newSamples - CHUNK_SAMPLES)
            mel.run(melIn, melOut)
            // The embedding model was trained on mels scaled this way.
            shiftIn(mels, melOut.size) { i -> melOut[i] / 10f + 2f }
            newMels = minOf(mels.size / NUM_MELS, newMels + melOut.size / NUM_MELS)
            while (newMels >= EMB_FEATURES) {
                System.arraycopy(mels, mels.size - newMels * NUM_MELS, embIn, 0, embIn.size)
                newMels = maxOf(0, newMels - EMB_STEP)
                embedding.run(embIn, embOut)
                score(embOut)
            }
        }
        val over = !lastScore.isNaN() && lastScore >= threshold
        streak = if (over) streak + 1 else 0
        if (streak < confirmations) return false
        streak = 0
        return true
    }

    private fun score(features: FloatArray) {
        shiftIn(embeddings, WW_FEATURES) { i -> features[i] }
        newEmbeddings = minOf(embeddings.size / WW_FEATURES, newEmbeddings + 1)
        while (newEmbeddings >= windows) {
            System.arraycopy(embeddings, embeddings.size - newEmbeddings * WW_FEATURES, wwIn, 0, wwIn.size)
            newEmbeddings = maxOf(0, newEmbeddings - 1)
            wakeword.run(wwIn, wwOut)
            if (lastScore.isNaN() || wwOut[0] > lastScore) lastScore = wwOut[0]
        }
    }

    /**
     * Forgets everything heard so far. As in pyopen-wakeword, the buffer then
     * counts as eight seconds of fresh silence, so detection works from the
     * first chunk; the next [process] call runs those windows too.
     */
    fun reset() {
        audio.fill(0f)
        mels.fill(0f)
        embeddings.fill(0f)
        newSamples = AUTOFILL_SECONDS * SAMPLE_RATE
        newMels = 0
        newEmbeddings = 0
        streak = 0
        lastScore = Float.NaN
    }

    override fun close() {
        mel.close()
        embedding.close()
        wakeword.close()
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHUNK_SAMPLES = 1280 // 80 ms
        // Hermes Agent's desktop default is 0.6 in 3 consecutive chunks. On the OnePlus 8T a
        // real "Hey Hermes" at 1-3 m peaked at 0.92-0.97 but often stayed over 0.6 for only
        // one or two chunks, so that rule missed about half of them (docs/android.md).
        const val DEFAULT_THRESHOLD = 0.8f
        const val DEFAULT_CONFIRMATIONS = 1
        const val MEL_MODEL = "melspectrogram.tflite"
        const val EMBEDDING_MODEL = "embedding_model.tflite"
        const val WAKEWORD_MODEL = "hey_hermes.tflite"

        // pyopen-wakeword 1.1.0's constants (openwakeword.py).
        private const val AUTOFILL_SECONDS = 8
        private const val MAX_SECONDS = 10
        private const val MEL_SAMPLES = 1760
        private const val MELS_PER_SECOND = 97
        private const val NUM_MELS = 32
        private const val EMB_FEATURES = 76
        private const val EMB_STEP = 8
        private const val WW_FEATURES = 96

        /** Shifts [buffer] left by [n] and writes value(0 until n) at its end. */
        private inline fun shiftIn(buffer: FloatArray, n: Int, value: (Int) -> Float) {
            System.arraycopy(buffer, n, buffer, 0, buffer.size - n)
            val start = buffer.size - n
            for (i in 0 until n) buffer[start + i] = value(i)
        }
    }
}

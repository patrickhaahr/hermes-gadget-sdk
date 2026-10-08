package io.github.adolanium.hermesgadget

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.log10

/** Local wake-phrase detection as the [AudioCoordinator] sees it. */
interface WakeEngine {
    /**
     * Starts a listening session with a fresh detector and returns where its
     * 16 kHz audio goes. [onDetected] gets the score of each detection, on the
     * engine's thread; after one the detector starts over.
     */
    fun arm(onDetected: (Float) -> Unit, onChunk: (ShortArray) -> Unit): (ShortArray) -> Unit

    /** Stops inference while continuing ordered chunks to the core recording. */
    fun takeCapture()

    /** Ends the session: audio still queued for it is dropped. */
    fun disarm()

    /** Why detection can't run (such as models that failed to load), or null. */
    val problem: String?

    fun close()
}

/**
 * Runs a [WakeDetector] on its own thread. The microphone's reader thread
 * hands it blocks, which it gathers into 80 ms chunks; when the detector falls
 * behind, the oldest chunks are dropped so it stays live. The audio goes
 * to the core only after a guarded wake handoff.
 *
 * For tuning on a phone, [log] gets each near miss (a score that rose without
 * a detection) and the input level once a minute: numbers only, never audio.
 */
class WakeListener(
    queueChunks: Int = QUEUE_CHUNKS,
    private val log: (String) -> Unit = {},
    load: () -> WakeDetector,
) : WakeEngine {
    private class Session(val onDetected: (Float) -> Unit, val onChunk: (ShortArray) -> Unit) {
        @Volatile var detecting = true
        val pending = ShortArray(WakeDetector.CHUNK_SAMPLES)
        var filled = 0
    }

    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(queueChunks),
        { r -> Thread(r, "wake-detector").apply { isDaemon = true } }, ThreadPoolExecutor.DiscardOldestPolicy())
    @Volatile private var session: Session? = null
    @Volatile private var failure: String? = null
    private var detector: WakeDetector? = null // detector thread only
    private var detectorSession: Session? = null // detector thread only
    // Detector thread only: the current rise in score, and the loudest input lately.
    private var risePeak = 0f
    private var riseChunks = 0
    private var riseLevel = MIN_DBFS
    private var level = MIN_DBFS
    private var levelChunks = 0

    init {
        executor.execute {
            detector = try {
                load()
            } catch (e: Exception) {
                failure = "the wake models did not load (${e.message ?: e.javaClass.simpleName})"
                null
            } catch (e: LinkageError) { // a runtime library missing for this phone's ABI
                failure = "the wake runtime is unavailable (${e.message ?: e.javaClass.simpleName})"
                null
            }
        }
    }

    override val problem: String? get() = failure

    override fun arm(onDetected: (Float) -> Unit, onChunk: (ShortArray) -> Unit): (ShortArray) -> Unit {
        val s = Session(onDetected, onChunk)
        session = s
        return { samples -> feed(s, samples) }
    }

    override fun takeCapture() { session?.detecting = false }

    override fun disarm() {
        session = null
    }

    // The microphone's reader thread.
    private fun feed(s: Session, samples: ShortArray) {
        var offset = 0
        while (offset < samples.size) {
            val n = minOf(samples.size - offset, s.pending.size - s.filled)
            System.arraycopy(samples, offset, s.pending, s.filled, n)
            s.filled += n
            offset += n
            if (s.filled == s.pending.size) {
                val chunk = s.pending.copyOf()
                s.filled = 0
                executor.execute { detect(s, chunk) }
            }
        }
    }

    // The detector thread.
    private fun detect(s: Session, chunk: ShortArray) {
        if (session !== s) return
        if (!s.detecting) {
            s.onChunk(chunk)
            return
        }
        val d = detector ?: return
        if (detectorSession !== s) {
            d.reset() // nothing heard before this session counts
            detectorSession = s
        }
        val fired = d.process(chunk)
        note(d.lastScore, dbfs(chunk), fired, d)
        if (fired) {
            val score = d.lastScore
            d.reset() // one utterance, one detection
            s.onDetected(score)
        }
        s.onChunk(chunk)
    }

    private fun note(score: Float, chunkLevel: Float, fired: Boolean, d: WakeDetector) {
        level = maxOf(level, chunkLevel)
        if (++levelChunks >= LEVEL_CHUNKS) {
            log("input level: loudest %.0f dBFS in the last minute".format(level))
            level = MIN_DBFS
            levelChunks = 0
        }
        if (!fired && !score.isNaN() && score >= NEAR_MISS) {
            risePeak = maxOf(risePeak, score)
            if (score >= d.threshold) riseChunks++
            riseLevel = maxOf(riseLevel, chunkLevel)
        } else {
            if (!fired && risePeak > 0f) {
                log("near miss: peak score %.3f, %d chunk(s) over the threshold, loudest %.0f dBFS"
                    .format(risePeak, riseChunks, riseLevel))
            }
            risePeak = 0f
            riseChunks = 0
            riseLevel = MIN_DBFS
        }
    }

    override fun close() {
        session = null
        executor.execute {
            detector?.close()
            detector = null
        }
        executor.shutdown()
    }

    companion object {
        const val QUEUE_CHUNKS = 25 // 2 s of audio
        private const val NEAR_MISS = 0.1f
        private const val LEVEL_CHUNKS = 750 // a minute of 80 ms chunks
        private const val MIN_DBFS = -96f

        private fun dbfs(chunk: ShortArray): Float {
            var peak = 1
            for (v in chunk) peak = maxOf(peak, abs(v.toInt()))
            return (20 * log10(peak / 32768.0)).toFloat()
        }
    }
}

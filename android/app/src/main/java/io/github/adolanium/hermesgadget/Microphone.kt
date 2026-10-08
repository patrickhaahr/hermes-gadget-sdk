package io.github.adolanium.hermesgadget

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/** Microphone capture as the [AudioCoordinator] sees it: one owner at a time. */
interface Capture {
    /** Starts mono PCM16 capture into [sink], replacing any running capture. */
    fun open(rate: Int, sink: (ShortArray) -> Unit): Boolean

    /** Stops capture; [sink] gets nothing more once this returns. */
    fun close()
}

/**
 * Mono PCM16 capture. A reader thread hands 20 ms blocks to the sink; the
 * caller forwards them to the thread that uses them.
 */
class Microphone : Capture {
    private var running: AtomicBoolean? = null
    private var record: AudioRecord? = null
    private var reader: Thread? = null

    /** False when the permission is missing or the hardware refuses the format. */
    @SuppressLint("MissingPermission") // Checked by the caller; a denial throws and is handled here.
    override fun open(rate: Int, sink: (ShortArray) -> Unit): Boolean {
        close()
        val block = rate / 50
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, block * 2 * 10))
        } catch (e: RuntimeException) {
            Log.w(TAG, "microphone unavailable", e)
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        try {
            rec.startRecording()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "microphone did not start", e)
            rec.release()
            return false
        }
        record = rec
        // Per capture, so a reader that outlives close() can't feed the next owner.
        val active = AtomicBoolean(true).also { running = it }
        reader = Thread({
            val buf = ShortArray(block)
            while (active.get()) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    if (active.get()) sink(buf.copyOf(n))
                } else if (n < 0) {
                    Log.w(TAG, "microphone read failed: $n")
                    break
                }
            }
        }, "gadget-mic").apply { start() }
        return true
    }

    override fun close() {
        running?.set(false)
        running = null
        reader?.join(STOP_WAIT_MS)
        reader = null
        record?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
            }
            it.release()
        }
        record = null
    }

    private companion object {
        const val TAG = "HermesGadgetMic"
        const val STOP_WAIT_MS = 500L
    }
}

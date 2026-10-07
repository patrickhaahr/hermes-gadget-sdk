package io.github.adolanium.hermesgadget

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * Mono PCM16 capture. A reader thread hands 20 ms blocks to [onSamples]; the
 * caller forwards them to the core thread.
 */
class Microphone(private val onSamples: (ShortArray) -> Unit) {
    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var reader: Thread? = null

    /** False when the permission is missing or the hardware refuses the format. */
    @SuppressLint("MissingPermission") // Checked by the caller; a denial throws and is handled here.
    fun start(rate: Int): Boolean {
        stop()
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
        running = true
        reader = Thread({
            val buf = ShortArray(block)
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) onSamples(buf.copyOf(n)) else if (n < 0) {
                    Log.w(TAG, "microphone read failed: $n")
                    break
                }
            }
        }, "gadget-mic").apply { start() }
        return true
    }

    fun stop() {
        running = false
        reader?.join(500)
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
    }
}

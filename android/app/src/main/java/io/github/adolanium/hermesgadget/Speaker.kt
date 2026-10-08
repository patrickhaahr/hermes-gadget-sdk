package io.github.adolanium.hermesgadget

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Streaming PCM16 playback for the core's AudioOut. write() never blocks the core
 * thread: blocks are queued for a writer thread that feeds a streaming AudioTrack.
 * busy() stays true until the stream has played out or was aborted.
 *
 * The server paces audio at most 0.5 s ahead of real time, so the queue stays short.
 */
class Speaker : Playback {
    private sealed interface Item {
        val generation: Int
    }

    private class Pcm(override val generation: Int, val samples: ShortArray) : Item
    private class End(override val generation: Int) : Item

    private val queue = LinkedBlockingQueue<Item>()
    private val active = AtomicBoolean(false)
    @Volatile private var generation = 0
    @Volatile private var volume = 0.7f
    @Volatile private var track: AudioTrack? = null
    @Volatile private var rate = 0
    // Writer thread only: frames written since the track was last flushed.
    private var framesWritten = 0L
    private var writtenGeneration = -1
    private val writer = Thread(::run, "gadget-speaker").apply {
        isDaemon = true
        start()
    }

    override fun begin(sampleRate: Int): Boolean {
        abort()
        val t = trackFor(sampleRate) ?: return false
        if (t.playState != AudioTrack.PLAYSTATE_PLAYING) {
            t.flush()
            t.play()
        }
        active.set(true)
        return true
    }

    override fun write(samples: ShortArray) {
        if (active.get()) queue.put(Pcm(generation, samples))
    }

    override fun end() {
        if (active.get()) queue.put(End(generation))
    }

    /** Drop everything buffered and stop now (barge-in, cancel). */
    override fun abort() {
        generation++
        queue.clear()
        track?.let {
            it.pause()
            it.flush()
        }
        active.set(false)
    }

    override fun busy(): Boolean = active.get()

    override fun setVolume(percent: Int) {
        volume = percent.coerceIn(0, 100) / 100f
        track?.setVolume(volume)
    }

    fun release() {
        abort()
        writer.interrupt()
        track?.release()
        track = null
    }

    private fun trackFor(sampleRate: Int): AudioTrack? {
        track?.let { if (rate == sampleRate) return it }
        track?.release()
        track = null
        val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(min, sampleRate)) // about 0.5 s
            .build()
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            t.release()
            return null
        }
        t.setVolume(volume)
        track = t
        rate = sampleRate
        return t
    }

    private fun run() {
        while (true) {
            val item = try {
                queue.take()
            } catch (_: InterruptedException) {
                return
            }
            val t = track ?: continue
            if (item.generation != generation) continue
            if (item.generation != writtenGeneration) {
                framesWritten = 0 // abort() or begin() flushed the track
                writtenGeneration = item.generation
            }
            when (item) {
                is Pcm -> {
                    if (t.playState == AudioTrack.PLAYSTATE_STOPPED) {
                        t.flush()
                        t.play()
                        framesWritten = 0
                    }
                    var offset = 0
                    while (offset < item.samples.size && item.generation == generation) {
                        val n = t.write(item.samples, offset, item.samples.size - offset)
                        if (n <= 0) break
                        offset += n
                        framesWritten += n
                    }
                }
                is End -> drain(t, item.generation)
            }
        }
    }

    // stop() on a streaming track plays out what it holds; wait for that before going idle.
    private fun drain(t: AudioTrack, gen: Int) {
        val head = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        val remainingMs = ((framesWritten - head).coerceAtLeast(0) * 1000 / rate)
        t.stop()
        val deadline = SystemClock.elapsedRealtime() + remainingMs + 300
        while (gen == generation && SystemClock.elapsedRealtime() < deadline) {
            val now = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            if (now >= framesWritten) break
            SystemClock.sleep(10)
        }
        framesWritten = 0
        if (gen == generation) active.set(false)
    }
}

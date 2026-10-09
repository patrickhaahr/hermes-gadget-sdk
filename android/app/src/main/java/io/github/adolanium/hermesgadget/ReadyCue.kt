package io.github.adolanium.hermesgadget

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * The ready cue: two rising tones, about a quarter of a second, played on the
 * call's audio path (voice communication) so it comes out where the call does.
 */
class ReadyCue : Cue {
    private var track: AudioTrack? = null

    override fun play() {
        stop()
        val pcm = chime()
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        t.write(pcm, 0, pcm.size)
        t.play()
        track = t
    }

    override fun stop() {
        track?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
            }
            it.release()
        }
        track = null
    }

    private fun chime(): ShortArray {
        val tone = RATE * TONE_MS / 1000
        val fade = RATE * 10 / 1000
        return ShortArray(tone * 2) { i ->
            val freq = if (i < tone) 660.0 else 880.0
            val at = i % tone
            val envelope = min(1.0, min(at, tone - at).toDouble() / fade)
            (sin(2 * PI * freq * i / RATE) * envelope * AMPLITUDE).toInt().toShort()
        }
    }

    private companion object {
        const val RATE = 24000
        const val TONE_MS = 120
        const val AMPLITUDE = 9000.0
    }
}

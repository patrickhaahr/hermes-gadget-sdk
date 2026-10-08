package io.github.adolanium.hermesgadget

import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in acoustic check against the paired production app and a real Hermes host.
 * The phone plays a synthetic input through its own loudspeaker; its real microphone
 * must hear the wake and request. This is not a human/distance accuracy measurement.
 * Run with: am instrument -w -e voiceAcoustic true -e class <this class> <runner>
 */
@RunWith(AndroidJUnit4::class)
class WakeRequestDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun screen(): String {
        val latch = CountDownLatch(1)
        var status = ""
        GadgetRuntime.core?.status { status = it; latch.countDown() }
        check(latch.await(3, TimeUnit.SECONDS)) { "Core did not answer" }
        return org.json.JSONObject(status).optString("screen")
    }

    private fun awaitReady() {
        val until = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < until) {
            if (GadgetRuntime.core != null && screen() == "ready" &&
                GadgetRuntime.core?.audioStatus?.state == AudioState.WAKE_LISTENING) return
            Thread.sleep(100)
        }
        error("Paired Hermes app did not become ready")
    }

    @Test
    fun loudspeakerInputProducesARealHermesReplyAndRearms() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("voiceAcoustic") == "true")
        val context = instrumentation.targetContext
        assertTrue("Enable the microphone before running", GadgetRuntime.microphone(context).enabled)
        assertEquals(VoiceMode.HERMES, GadgetRuntime.voiceMode(context))
        instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        awaitReady()
        // Let the real microphone establish its wake history before the input starts.
        Thread.sleep(1500)
        if (InstrumentationRegistry.getArguments().getString("screenOff") == "true") {
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_SLEEP").close()
            Thread.sleep(500)
        }
        val before = GadgetRuntime.core!!.audioStatus.detections
        val pcm = instrumentation.context.assets.open(WakeFixtures.REQUEST).use { WakeFixtures.pcm(it.readBytes()) }
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
        try {
            assertEquals(pcm.size, track.write(pcm, 0, pcm.size))
            val start = SystemClock.elapsedRealtime()
            track.play()
            var captureAt = 0L
            var replyAt = 0L
            val deadline = start + 90_000
            while (SystemClock.elapsedRealtime() < deadline) {
                val status = GadgetRuntime.core!!.audioStatus
                if (status.state == AudioState.GADGET_CAPTURE && captureAt == 0L) captureAt = SystemClock.elapsedRealtime()
                if (status.state == AudioState.GADGET_PLAYBACK && replyAt == 0L) replyAt = SystemClock.elapsedRealtime()
                if (replyAt > 0 && status.state == AudioState.WAKE_LISTENING && screen() == "ready") break
                Thread.sleep(20)
            }
            assertTrue("No acoustic wake request", captureAt > 0)
            assertTrue("Hermes did not play a reply", replyAt > 0)
            assertEquals("A reply must not wake the phone", before + 1, GadgetRuntime.core!!.audioStatus.detections)
            assertEquals(AudioState.WAKE_LISTENING, GadgetRuntime.core!!.audioStatus.state)
            assertEquals("ready", screen())
            Log.i("HermesVoiceTest", "acoustic input: wake ${captureAt - start} ms, reply ${replyAt - start} ms, one detection; rearmed")
        } finally {
            track.stop()
            track.release()
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
        }
    }

    @Test
    fun aWakeWithoutSpeechDiscardsOnTheRealMicrophone() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("voiceAcoustic") == "true")
        val context = instrumentation.targetContext
        instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        awaitReady()
        Thread.sleep(1500)
        val before = GadgetRuntime.core!!.audioStatus.detections
        val pcm = instrumentation.context.assets.open(WakeFixtures.POSITIVE_LJSPEECH).use { WakeFixtures.pcm(it.readBytes()) }
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
        try {
            assertEquals(pcm.size, track.write(pcm, 0, pcm.size))
            track.play()
            var heard = false
            var playback = false
            val started = SystemClock.elapsedRealtime()
            val until = started + 12_000
            while (SystemClock.elapsedRealtime() < until) {
                val state = GadgetRuntime.core!!.audioStatus.state
                heard = heard || state == AudioState.GADGET_CAPTURE
                playback = playback || state == AudioState.GADGET_PLAYBACK
                if (heard && state == AudioState.WAKE_LISTENING && screen() == "ready") break
                Thread.sleep(20)
            }
            assertTrue("No acoustic detection", heard)
            assertTrue("Silence played a reply", !playback)
            assertEquals(before + 1, GadgetRuntime.core!!.audioStatus.detections)
            assertEquals("ready", screen())
            Log.i("HermesVoiceTest", "wake without request: discarded and rearmed in ${SystemClock.elapsedRealtime() - started} ms; no playback")
        } finally {
            track.stop()
            track.release()
        }
    }

}

package io.github.adolanium.hermesgadget

import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.util.concurrent.CopyOnWriteArrayList

/** Opt-in acoustic Live qualification on a paired phone and its configured Hermes host,
 * run through android/tools/live_acoustic.py. The phone plays synthetic "Hey Hermes" and
 * a hold-to-talk request through its own loudspeaker into the production core, wake
 * detector and WebRTC call. The phone's echo canceller removes its own media playback
 * from a call, so this measures wake, readiness, the ready cue's echo, End call, idle
 * expiry and re-arming; it cannot speak to the voice like a person at 1–3 m.
 * Logs timings and counts only.
 */
@RunWith(AndroidJUnit4::class)
class LiveAcousticDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()

    /** Records the voice service's data-channel events for the running call. */
    private class Observed(val delegate: CallMediaEvents) : CallMediaEvents {
        class Event(val at: Long, val type: String, val user: Boolean, val text: String)
        val events = CopyOnWriteArrayList<Event>()
        val failures = CopyOnWriteArrayList<String>()

        override fun offer(sdp: String) = delegate.offer(sdp)
        override fun failed(reason: String) {
            failures += reason
            delegate.failed(reason)
        }

        override fun message(text: String) {
            val event = runCatching { JSONObject(text) }.getOrNull()
            if (event != null) {
                val type = event.optString("type")
                val item = event.optJSONObject("item")
                events += Event(SystemClock.elapsedRealtime(), type, type == "input_transcript.added", item?.optString("text") ?: "")
            }
            delegate.message(text)
        }
    }

    private fun speech(name: String): ShortArray =
        instrumentation.uiAutomation.executeShellCommand("cat ${args.getString("speech")}/$name").use { fd ->
            WakeFixtures.pcm(FileInputStream(fd.fileDescriptor).use { it.readBytes() })
        }

    private fun wake(): ShortArray =
        instrumentation.context.assets.open(WakeFixtures.POSITIVE_LJSPEECH).use { WakeFixtures.pcm(it.readBytes()) }

    /** Plays [pcm] on the loudspeaker as media and returns when it has finished. */
    private fun play(pcm: ShortArray) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
        try {
            assertEquals(pcm.size, track.write(pcm, 0, pcm.size))
            track.play()
            Thread.sleep(pcm.size / 16L + 100)
        } finally {
            track.stop()
            track.release()
        }
    }

    private fun await(description: String, timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        log("timed out: $description")
        return false
    }

    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).close()

    private fun log(line: String) = Log.i(TAG, line)

    @Test
    fun syntheticSpeechThroughTheLiveLoop() {
        assumeTrue(args.getString("liveAcoustic") == "true")
        val context = instrumentation.targetContext
        assertTrue("Enable the microphone before running", GadgetRuntime.microphone(context).enabled)
        assertEquals("Select Live voice before running", VoiceMode.LIVE, GadgetRuntime.voiceMode(context))
        instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(await("configured core", 20_000) { GadgetRuntime.core?.callStatus?.unavailable == null })

        // Replace the service's core with one whose call events are observed; the
        // drivers, wake models, WebRTC and identity are the production ones.
        GadgetRuntime.core!!.stop()
        val calls = WebRtcCalls(context)
        val observed = CopyOnWriteArrayList<Observed>()
        val (width, height) = GadgetRuntime.frameSize(context)
        val core = GadgetCore(GadgetRuntime.store(context),
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0",
            Frame(width, height), GadgetRuntime.microphone(context),
            WakeListener { WakeDetector(LiteRtModel.fromAssets(context.assets)) },
            onFrame = { GadgetRuntime.frameListener?.invoke() },
            onAudio = { GadgetRuntime.audioListener?.invoke(it) },
            onStopped = { error(it) },
            voiceMode = { GadgetRuntime.voiceMode(context) },
            callMedia = CallMediaFactory { events -> calls.create(Observed(events).also(observed::add)) },
            cue = ReadyCue(),
            onCall = { GadgetRuntime.callListener?.invoke(it) })
        GadgetRuntime.core = core
        val audioManager = context.getSystemService(AudioManager::class.java)
        try {
            core.start()
            core.network(true, "Wi-Fi")
            assertTrue(await("paired phone", 20_000) { core.callStatus.unavailable == null })
            when (args.getString("scenario", "conversation")) {
                "conversation" -> conversation(core, observed, audioManager, (args.getString("cycles") ?: "3").toInt())
                "failure" -> failure(core)
                "hold_to_talk" -> holdToTalk(core)
                else -> error("unknown scenario")
            }
        } finally {
            shell("input keyevent KEYCODE_WAKEUP")
            if (core.callStatus.state != CallState.IDLE) core.endCall()
            core.stop()
            GadgetRuntime.core = null
            GadgetService.start(context)
            await("ordinary core restored", 20_000) { GadgetRuntime.core != null }
            GadgetRuntime.core!!.network(true, "Wi-Fi")
            await("ordinary paired connection restored", 20_000) { GadgetRuntime.core!!.callStatus.unavailable == null }
        }
    }

    private fun idleListening(core: GadgetCore) = core.callStatus.state == CallState.IDLE &&
        core.callStatus.unavailable == null && core.audioStatus.state == AudioState.WAKE_LISTENING

    /** Wake, then wait for the call to become ready; null when it didn't. */
    private fun wakeToCall(core: GadgetCore, label: String): Long? {
        assertTrue(await("wake listening", 30_000) { idleListening(core) })
        Thread.sleep(1500) // the detector's feature history
        if (args.getString("screenOff") == "true") {
            shell("input keyevent KEYCODE_SLEEP")
            Thread.sleep(500)
        }
        val detections = core.audioStatus.detections
        play(wake())
        val detected = await("$label detection", 3000) { core.audioStatus.detections > detections }
        if (!detected) {
            log("$label: no detection")
            return null
        }
        // LiveCall.start clears the previous outcome, so a failure here is this attempt's.
        val settled = await("$label ready", 25_000) {
            core.callStatus.state == CallState.ACTIVE ||
                core.callStatus.state == CallState.IDLE && core.callStatus.feedback?.startsWith("Live call failed") == true
        }
        if (!settled || core.callStatus.state != CallState.ACTIVE) {
            log("$label: detected, call not ready (${core.callStatus.feedback?.substringBefore(':')})")
            return null
        }
        log("$label: detected; ready ${core.callStatus.readyMs} ms after detection")
        return core.callStatus.readyMs
    }

    /**
     * Each cycle: a loudspeaker wake starts the call, then nobody speaks, so any user
     * transcript is the ready cue, room noise or a person. Odd cycles hang up with End
     * call after a quiet window; even cycles wait for the 60 s idle expiry. Each must
     * release the call's audio and re-arm wake listening.
     */
    private fun conversation(core: GadgetCore, observed: List<Observed>, audioManager: AudioManager, cycles: Int) {
        var ready = 0
        var userDeltas = 0
        for (cycle in 1..cycles) {
            val label = "cycle $cycle"
            wakeToCall(core, label) ?: continue
            ready++
            val call = observed.last()
            val readyAt = SystemClock.elapsedRealtime()
            val endedAt: Long
            if (cycle % 2 == 1) {
                Thread.sleep(QUIET_MS)
                endedAt = SystemClock.elapsedRealtime()
                core.endCall()
                assertTrue(await("$label End call", 5000) { core.callStatus.state == CallState.IDLE })
            } else {
                assertTrue(await("$label idle expiry", 90_000) { core.callStatus.state == CallState.IDLE })
                endedAt = SystemClock.elapsedRealtime()
                val lastSpeech = call.events.lastOrNull { it.type.endsWith("_transcript.added") && it.text.isNotBlank() }?.at ?: readyAt
                log("$label idle: ${core.callStatus.feedback}, ${endedAt - lastSpeech} ms after readiness or the last transcript")
            }
            assertTrue(await("$label rearm", 10_000) { idleListening(core) })
            log("$label rearmed ${SystemClock.elapsedRealtime() - endedAt} ms after hang-up began")
            assertEquals(AudioManager.MODE_NORMAL, audioManager.mode)
            val deltas = call.events.count { it.user && it.text.isNotBlank() }
            userDeltas += deltas
            log("$label: $deltas user transcript deltas with no prompt playing; failures ${call.failures.size}")
            shell("input keyevent KEYCODE_WAKEUP")
        }
        log("summary: $cycles wakes, $ready ready, $userDeltas user transcript deltas")
        assertEquals("Every wake should start a usable call", cycles, ready)
    }

    /** Run while the host's app-server is stopped: wake, startup failure and re-arm. */
    private fun failure(core: GadgetCore) {
        val ready = wakeToCall(core, "failure")
        if (ready == null) {
            assertTrue("No startup failure", core.callStatus.feedback?.startsWith("Live call failed") == true)
            val started = SystemClock.elapsedRealtime()
            assertTrue(await("rearm after failure", 30_000) { idleListening(core) })
            log("failure: ${core.callStatus.feedback}; rearmed ${SystemClock.elapsedRealtime() - started} ms later")
        } else {
            core.endCall()
            error("The call became ready; stop the app-server first")
        }
    }

    /** Hold-to-talk in Live mode still records a Hermes request and plays its reply. */
    private fun holdToTalk(core: GadgetCore) {
        assertTrue(await("wake listening", 30_000) { idleListening(core) })
        val detections = core.audioStatus.detections
        shell("input motionevent DOWN 540 1200")
        assertTrue(await("hold-to-talk capture", 3000) { core.audioStatus.state == AudioState.GADGET_CAPTURE })
        Thread.sleep(300)
        play(speech("request.wav"))
        shell("input motionevent UP 540 1200")
        val released = SystemClock.elapsedRealtime()
        assertTrue(await("reply playback", 60_000) { core.audioStatus.state == AudioState.GADGET_PLAYBACK })
        val reply = SystemClock.elapsedRealtime()
        assertTrue(await("rearm after reply", 90_000) { idleListening(core) })
        assertEquals(CallState.IDLE, core.callStatus.state)
        assertEquals(detections, core.audioStatus.detections)
        log("hold-to-talk: reply ${reply - released} ms after release; rearmed; no wake, no call")
    }

    private companion object {
        const val TAG = "HermesAcousticTest"
        const val QUIET_MS = 15_000L
    }
}

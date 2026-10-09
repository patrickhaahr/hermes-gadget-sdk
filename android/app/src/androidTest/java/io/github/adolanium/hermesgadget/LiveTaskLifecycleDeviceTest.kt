package io.github.adolanium.hermesgadget

import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Opt-in qualification on a paired phone and its configured, real Hermes host.
 * Uses real WebRTC capture/playback and the production core/controller/transport.
 * Delegations are scripted at CallMediaEvents, rather than spoken: this measures
 * platform/task lifetime, not speech recognition or audible native busy feedback.
 * Each scenario asks Hermes to run a read-only, 20-second terminal task. Calls
 * spend subscription allowance. No task/audio/transcript content is logged here.
 * Run through android/tools/live_task_lifecycle.py on the paired Hermes host.
 */
@RunWith(AndroidJUnit4::class)
class LiveTaskLifecycleDeviceTest {
    private class ObservedMedia(val delegate: CallMedia, val events: CallMediaEvents) : CallMedia by delegate {
        val responses = CopyOnWriteArrayList<String>()
        @Volatile var closed = false

        override fun respond(delegation: String, text: String): Boolean {
            responses += delegation
            return delegate.respond(delegation, text)
        }

        override fun close() {
            delegate.close()
            closed = true
        }
    }

    private fun delegation(id: String, text: String) = JSONObject().put("type", "delegation.created")
        .put("item", JSONObject().put("id", id).put("content", org.json.JSONArray().put(JSONObject().put("text", text)))).toString()

    private fun await(description: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        error("Timed out: $description")
    }

    private fun screen(core: GadgetCore): String {
        val latch = CountDownLatch(1)
        var json = "{}"
        core.status { json = it; latch.countDown() }
        check(latch.await(3, TimeUnit.SECONDS)) { "Core did not answer" }
        return JSONObject(json).getString("screen")
    }

    @Test
    fun acceptedWorkFinishesSilentlyAcrossVoiceTeardown() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveLifecycle") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val savedMicrophone = GadgetRuntime.microphone(context).enabled
        assertTrue("Enable the microphone before running", savedMicrophone)
        instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        await("configured core") { GadgetRuntime.core != null && GadgetRuntime.core?.callStatus?.unavailable == null }

        // Use the existing production factory seam to script a task while retaining
        // the phone's actual WebRTC/audio drivers. Preserve the service, identity,
        // configuration and foreground locks; restore its ordinary core afterwards.
        GadgetRuntime.core!!.stop()
        val media = CopyOnWriteArrayList<ObservedMedia>()
        val audioManager = context.getSystemService(AudioManager::class.java)
        val gadgetSpoke = AtomicBoolean(false)
        val playback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                // The public callback supplies active configurations only.
                if (configs.any { it.audioAttributes.usage == AudioAttributes.USAGE_ASSISTANT }) {
                    gadgetSpoke.set(true)
                }
            }
        }
        audioManager.registerAudioPlaybackCallback(playback, Handler(Looper.getMainLooper()))
        val calls = WebRtcCalls(context)
        val (width, height) = GadgetRuntime.frameSize(context)
        val core = GadgetCore(GadgetRuntime.store(context),
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0",
            Frame(width, height), GadgetRuntime.microphone(context),
            WakeListener { WakeDetector(LiteRtModel.fromAssets(context.assets)) },
            onFrame = {}, onAudio = {}, onStopped = { error(it) },
            callMedia = CallMediaFactory { events ->
                calls.create(events)?.let { ObservedMedia(it, events).also(media::add) }
            }, cue = ReadyCue())
        GadgetRuntime.core = core
        try {
            core.start()
            core.network(true, "Wi-Fi")
            await("paired phone") { core.callStatus.unavailable == null }
            for (teardown in listOf("hangup", "microphone_off", "reconnect", "media_failure")) {
                await("wake listening") { core.audioStatus.state == AudioState.WAKE_LISTENING }
                core.startCall()
                await("usable WebRTC call") { core.callStatus.state == CallState.ACTIVE }
                val old = media.last()
                val token = "hg7-${UUID.randomUUID()}"
                Log.i("HermesLifecycleTest", "$teardown marker $token; ready ${core.callStatus.readyMs} ms")
                old.events.message(delegation("task-$token",
                    "Use the terminal to run exactly: sleep 20; printf '$token\\n'; hostname. " +
                        "Wait for completion and report the marker and hostname. Do not start a background job."))
                await("Hermes acceptance") { core.callStatus.feedback == "Hermes is working; you can keep talking" }
                await("task on screen") { screen(core) == "thinking" }
                gadgetSpoke.set(false)
                val endedAt = SystemClock.elapsedRealtime()
                when (teardown) {
                    "hangup" -> core.endCall()
                    "microphone_off" -> GadgetRuntime.setMicrophoneEnabled(context, false)
                    "reconnect" -> core.console("reconnect") {}
                    "media_failure" -> old.events.failed("scripted media failure")
                }
                await("voice release") { old.closed && core.callStatus.state == CallState.IDLE }
                assertEquals(AudioManager.MODE_NORMAL, audioManager.mode)
                assertTrue(old.responses.isEmpty())
                Log.i("HermesLifecycleTest", "$teardown released in ${SystemClock.elapsedRealtime() - endedAt} ms")

                var next: ObservedMedia? = null
                if (teardown == "microphone_off") {
                    assertFalse(GadgetRuntime.microphone(context).enabled)
                    assertEquals(AudioState.MICROPHONE_OFF, core.audioStatus.state)
                } else {
                    await("rearmed paired phone") {
                        core.callStatus.unavailable == null && core.audioStatus.state == AudioState.WAKE_LISTENING
                    }
                    core.startCall()
                    await("fresh call") { core.callStatus.state == CallState.ACTIVE }
                    next = media.last()
                    next.events.message(delegation("busy-$token", "Report the hostname"))
                    await("busy receipt") { core.callStatus.feedback == "Hermes is busy; wait, then ask again" }
                }
                // The host companion signals only after checking the actual
                // terminal result and final answer in Hermes's stored history.
                // The display can settle interim text before the task finishes.
                await("stored Hermes completion", 180_000) {
                    instrumentation.uiAutomation.executeShellCommand("getprop debug.hg7.completed").use { fd ->
                        FileInputStream(fd.fileDescriptor).use { it.readBytes().decodeToString().trim() == token }
                    }
                }
                Thread.sleep(3000) // include terminal delivery and any delayed audio
                assertFalse("The gadget played assistant audio", gadgetSpoke.get())
                assertTrue(old.responses.isEmpty())
                if (next != null) {
                    assertEquals(listOf("busy-$token"), next.responses.toList())
                    assertEquals(CallState.ACTIVE, core.callStatus.state)
                    core.endCall()
                } else {
                    assertEquals(CallState.IDLE, core.callStatus.state)
                    assertEquals(AudioState.MICROPHONE_OFF, core.audioStatus.state)
                    GadgetRuntime.setMicrophoneEnabled(context, true)
                }
                Log.i("HermesLifecycleTest", "$teardown stored completion stayed silent after ${SystemClock.elapsedRealtime() - endedAt} ms")
            }
        } finally {
            audioManager.unregisterAudioPlaybackCallback(playback)
            core.stop()
            GadgetRuntime.core = null
            GadgetRuntime.microphone(context).enabled = savedMicrophone
            GadgetService.start(context)
            await("ordinary core restored") { GadgetRuntime.core != null }
            GadgetRuntime.core!!.network(true, "Wi-Fi")
            await("ordinary paired connection restored") { GadgetRuntime.core!!.callStatus.unavailable == null }
        }
    }
}

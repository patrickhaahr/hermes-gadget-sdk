package io.github.adolanium.hermesgadget

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Microphone and speaker ownership with the production device core (through
 * the JNI bridge), the production [AudioCoordinator] and wake pipeline with
 * the real models, and stand-ins for Android's microphone and speaker. The
 * core's audio callbacks reach the coordinator exactly as in GadgetCore.
 */
class AudioCoordinatorTest {
    /** The microphone: fails the test if a second owner opens it while one has it. */
    private class FakeCapture : Capture {
        var sink: ((ShortArray) -> Unit)? = null
        val opens = mutableListOf<Int>()
        var refuse = false

        override fun open(rate: Int, sink: (ShortArray) -> Unit): Boolean {
            check(this.sink == null) { "the microphone was opened while another owner had it" }
            if (refuse) return false
            this.sink = sink
            opens += rate
            return true
        }

        override fun close() {
            sink = null
        }

        /** Delivers [pcm] in 20 ms blocks, as the reader thread does; false when nobody is capturing. */
        fun hear(pcm: ShortArray): Boolean {
            val s = sink ?: return false
            for (at in pcm.indices step BLOCK) s(pcm.copyOfRange(at, minOf(pcm.size, at + BLOCK)))
            return true
        }
    }

    private inner class FakePlayback : Playback {
        var playing = false
        val played = mutableListOf<Short>()

        override fun begin(sampleRate: Int): Boolean {
            check(capture.sink == null) { "playback started while the microphone was open" }
            playing = true
            return true
        }
        override fun write(samples: ShortArray) {
            played += samples.toList()
        }
        override fun end() {}
        override fun abort() {
            playing = false
        }
        override fun busy() = playing
        override fun setVolume(percent: Int) {}
    }

    private class Setting : MicrophoneSetting {
        override var enabled = true
    }

    private inner class Host : NativeHost {
        val storage = mutableMapOf("server" to "ws://zaza:8765/gadget", "name" to "8T")
        val sentText = mutableListOf<String>()
        val binary = mutableListOf<ByteArray>()
        val sentBinary get() = binary.size
        override fun transportConnect(url: ByteArray, subprotocol: ByteArray) {}
        override fun transportSendText(data: ByteArray) = sentText.add(data.decodeToString())
        override fun transportSendBinary(data: ByteArray): Boolean {
            binary += data.copyOf()
            return true
        }
        override fun transportClose() {}
        override fun displayFlush(y0: Int, y1: Int) {}
        override fun displayBacklight(percent: Int) {}
        override fun micStart(rate: Int) = audio.micStart(rate)
        override fun micStop() = audio.micStop()
        override fun speakerBegin(rate: Int) = audio.speakerBegin(rate)
        override fun speakerWrite(samples: ShortArray) = audio.speakerWrite(samples)
        override fun speakerEnd() = audio.speakerEnd()
        override fun speakerAbort() = audio.speakerAbort()
        override fun speakerBusy() = audio.speakerBusy()
        override fun speakerVolume(percent: Int) = audio.speakerVolume(percent)
        override fun storageGet(key: ByteArray) = storage[key.decodeToString()]?.toByteArray()
        override fun storageSet(key: ByteArray, value: ByteArray) {
            storage[key.decodeToString()] = value.decodeToString()
        }
        override fun storageErase(key: ByteArray) {
            storage.remove(key.decodeToString())
        }
        override fun nowMs() = clock
        override fun randomBytes(count: Int) = ByteArray(count) { (it * 7 + 1).toByte() }
        override fun log(level: Int, message: ByteArray) {}
    }

    private val capture = FakeCapture()
    private val playback = FakePlayback()
    private val setting = Setting()
    private val host = Host()
    private val queue = ConcurrentLinkedQueue<Runnable>() // the core thread's queue
    private val statuses = mutableListOf<AudioStatus>()
    private var clock = 0L
    private var handle = 0L
    private var mode = VoiceMode.HERMES
    private var deliveredSamples = 0
    private lateinit var models: WakeModels
    private lateinit var audio: AudioCoordinator

    private fun coordinator(wake: WakeEngine?) = AudioCoordinator(capture, playback, wake, setting, queue::add,
        toCore = { samples -> deliveredSamples += samples.size; NativeCore.micSamples(handle, samples, samples.size) }, onStatus = { statuses += it },
        startRequest = { NativeCore.startWakeRequest(handle).decodeToString() },
        discardRequest = { NativeCore.discardWakeRequest(handle) }, voiceMode = { mode })

    private fun wakeListener() = WakeListener(queueChunks = 1000) { WakeDetector(models) }

    @Before
    fun create() {
        models = HostTfliteModel.models()
        audio = coordinator(wakeListener())
        handle = NativeCore.create(host, 360, 800, hasMic = true, hasSpeaker = true, micRate = 16000,
            speakerRate = 24000, board = "android".toByteArray(), firmware = "0.2.0".toByteArray(),
            defaultName = "Android Gadget".toByteArray(), talkLabel = null, cancelLabel = null, touch = true)
        NativeCore.begin(handle)
        audio.start()
        online()
    }

    @After
    fun destroy() {
        audio.close()
        NativeCore.destroy(handle)
        assertNull("teardown did not reopen capture", capture.sink)
    }

    private fun pump() {
        while (true) (queue.poll() ?: return).run()
    }

    private fun run(ms: Int) {
        repeat(ms / 10) {
            clock += 10
            NativeCore.tick(handle)
            audio.tick(clock)
            pump()
        }
    }

    /** Waits for the detector thread, which works through the audio at its own pace. */
    private fun awaitDetections(count: Int) {
        val deadline = System.currentTimeMillis() + 20_000
        while (audio.status.detections < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            pump()
        }
        assertEquals(count, audio.status.detections)
    }

    private fun online() {
        NativeCore.network(handle, true, "Wi-Fi".toByteArray())
        run(2000)
        NativeCore.transportOpen(handle)
        run(50)
        val nonce = Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })
        NativeCore.transportText(handle, """{"type":"challenge","nonce":"$nonce","enrolled":false}""".toByteArray())
        run(50)
        NativeCore.transportText(handle,
            """{"type":"welcome","session":"s1","paired":true,"heartbeat_s":20,"server":"hermes","proto":1}""".toByteArray())
        run(50)
    }

    private fun sent(type: String) = host.sentText.count { "\"type\":\"$type\"" in it }

    private fun talk(pressed: Boolean) {
        NativeCore.button(handle, NativeCore.BUTTON_TALK, pressed)
        pump()
    }

    private val wakePhrase get() = HostFixtures.pcm(WakeFixtures.POSITIVE_LJSPEECH)

    @Test
    fun wakeStartsARequestWithoutUploadingBeforeSubmission() {
        assertEquals(AudioState.WAKE_LISTENING, audio.status.state)
        assertEquals(listOf(WakeDetector.SAMPLE_RATE), capture.opens)
        val textBefore = host.sentText.toList()

        assertTrue(capture.hear(HostFixtures.pcm(WakeFixtures.UNRELATED) + wakePhrase))
        awaitDetections(1)
        run(500)

        assertEquals("no audio frame went to Hermes", 0, host.sentBinary)
        assertEquals("nothing was sent for the detection", textBefore, host.sentText)
        assertEquals("listening", NativeCore.screen(handle).decodeToString())
        assertEquals(AudioState.GADGET_CAPTURE, audio.status.state)
        assertNotNull(audio.status.lastDetectionScore)
    }

    private fun awaitOutcome(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
            pump()
        }
        assertTrue("the expected outcome did not arrive", condition())
    }

    private fun uploaded(): ShortArray = host.binary.flatMap { frame ->
        val pcm = ShortArray((frame.size - 4) / 2)
        ByteBuffer.wrap(frame, 4, frame.size - 4).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
        pcm.toList()
    }.toShortArray()

    @Test
    fun wakeRequestUploadsExactlyFromDetectionAndPlaysOneReply() {
        val lead = HostFixtures.pcm(WakeFixtures.UNRELATED)
        val input = lead + HostFixtures.pcm(WakeFixtures.REQUEST)
        val expected = WakeFixtures.expected(java.io.File(System.getProperty("hg.wake.fixtures"), "expected.txt").readText())
        val detection = expected.getValue(WakeFixtures.REQUEST).detections.single()
        capture.hear(input)
        awaitOutcome { sent("audio.end") == 1 }
        assertEquals(1, sent("audio.start"))
        assertEquals("capture was handed over without reopening", listOf(16000, 16000), capture.opens)
        val bytes = uploaded()
        val from = lead.size + detection * WakeDetector.CHUNK_SAMPLES
        org.junit.Assert.assertArrayEquals(input.copyOfRange(from, from + bytes.size), bytes)
        assertTrue("the entire request, including its first word, arrived", bytes.size > 32000)

        NativeCore.transportText(handle, """{"type":"reply","id":"a1","text":"Two cups of tea.","final":true}""".toByteArray())
        NativeCore.transportText(handle, """{"type":"audio.start","stream":7,"rate":24000,"format":"pcm16"}""".toByteArray())
        NativeCore.transportBinary(handle, byteArrayOf(1, 7, 0, 0, 1, 0, 2, 0))
        NativeCore.transportText(handle, """{"type":"audio.end","stream":7}""".toByteArray())
        NativeCore.transportText(handle, """{"type":"turn.end"}""".toByteArray())
        assertEquals(listOf<Short>(1, 2), playback.played)
        assertFalse(capture.hear(wakePhrase))
        playback.playing = false
        run(600)
        assertEquals(AudioState.WAKE_LISTENING, audio.status.state)
    }

    @Test
    fun wakeWithoutARequestDiscardsLocallyAndRearms() {
        val before = host.sentText.toList()
        capture.hear(wakePhrase + ShortArray(96000))
        awaitDetections(1)
        awaitOutcome { NativeCore.screen(handle).decodeToString() == "ready" }
        assertEquals(before, host.sentText)
        assertEquals(0, host.sentBinary)
        assertEquals(AudioState.WAKE_LISTENING, audio.status.state)
        capture.hear(wakePhrase)
        awaitDetections(2)
        assertEquals("listening", NativeCore.screen(handle).decodeToString())
    }

    @Test
    fun microphoneOffAndSwipeDiscardTheBufferedWakeRequest() {
        for (off in listOf(false, true)) {
            capture.hear(wakePhrase)
            awaitDetections(if (off) 2 else 1)
            assertEquals("listening", NativeCore.screen(handle).decodeToString())
            if (off) audio.setMicrophoneEnabled(false) else {
                NativeCore.touch(handle, true, 180, 100)
                NativeCore.touch(handle, true, 180, 250)
                NativeCore.touch(handle, false, 180, 250)
            }
            run(100)
            assertEquals("ready", NativeCore.screen(handle).decodeToString())
            assertEquals(0, host.sentBinary)
            assertEquals(0, sent("audio.start"))
            assertEquals(0, sent("audio.end"))
        }
        assertEquals(AudioState.MICROPHONE_OFF, audio.status.state)
        assertFalse(setting.enabled)
    }

    @Test
    fun wakeRefusesDisconnectedUnpairedAndBusyWithoutSending() {
        val cases = listOf("disconnected", "unpaired", "busy")
        for ((index, case) in cases.withIndex()) {
            NativeCore.transportClosed(handle, "test".toByteArray())
            if (case != "disconnected") online()
            when (case) {
                "unpaired" -> NativeCore.transportText(handle, """{"type":"unpaired"}""".toByteArray())
                "busy" -> NativeCore.submitText(handle, "test".toByteArray())
            }
            val before = host.sentText.toList()
            capture.hear(wakePhrase)
            awaitDetections(index + 1)
            assertEquals(before, host.sentText)
            assertEquals(0, host.sentBinary)
            assertEquals(AudioState.WAKE_LISTENING, audio.status.state)
            val reason = when (case) {
                "disconnected" -> "Not connected to Hermes"
                "unpaired" -> "Approve pairing first"
                else -> "A turn is already running"
            }
            assertEquals(reason, audio.status.feedback)
        }
    }

    @Test
    fun maximumLengthSubmitsOnceAndACompletedOrFailedTurnRearms() {
        capture.hear(wakePhrase)
        awaitDetections(1)
        capture.hear(HostFixtures.pcm(WakeFixtures.UNRELATED).copyOfRange(8000, 64000))
        awaitOutcome { statuses.any { it.state == AudioState.GADGET_CAPTURE } }
        // Wait for real detector/reader delivery before advancing the fake clock.
        awaitOutcome { deliveredSamples >= 56000 }
        run(30100)
        assertEquals(NativeCore.status(handle).decodeToString(), 1, sent("audio.end"))
        assertEquals(1, sent("audio.start"))
        NativeCore.transportText(handle, """{"type":"turn.end"}""".toByteArray())
        run(100)
        capture.hear(wakePhrase)
        awaitDetections(2)
        assertEquals("listening", NativeCore.screen(handle).decodeToString())
    }

    @Test
    fun holdToTalkTakesTheMicrophoneFromWakeListeningEvenInLiveMode() {
        mode = VoiceMode.LIVE
        // Talk said while wake listening is never part of the recording.
        capture.hear(ShortArray(16000) { 1000 })
        talk(true)
        assertEquals(AudioState.GADGET_CAPTURE, audio.status.state)
        assertEquals(listOf(WakeDetector.SAMPLE_RATE, 16000), capture.opens)
        assertEquals(1, sent("audio.start"))
        assertEquals("audio from before the press stayed on the phone", 0, host.sentBinary)

        capture.hear(ShortArray(16000) { 2000 })
        run(1000)
        assertTrue("the recording reached Hermes", host.sentBinary > 0)
        talk(false)
        run(50)
        assertEquals(1, sent("audio.end"))
        assertEquals(AudioState.WAKE_LISTENING, audio.status.state)

        // Wake listening is back, with its own fresh session.
        val framesSent = host.sentBinary
        capture.hear(wakePhrase)
        awaitDetections(1)
        assertEquals(framesSent, host.sentBinary)
    }

    @Test
    fun microphoneOffStopsAllCaptureAndOutlastsARestart() {
        audio.setMicrophoneEnabled(false)
        assertEquals(AudioState.MICROPHONE_OFF, audio.status.state)
        assertNull("wake capture stopped", capture.sink)
        assertFalse(setting.enabled)

        talk(true)
        run(700)
        talk(false)
        assertEquals("hold-to-talk didn't open the microphone", 1, capture.opens.size)
        assertEquals(0, sent("audio.start"))
        assertEquals(0, host.sentBinary)

        // A new service or app process starts from the saved choice.
        audio.close()
        audio = coordinator(wakeListener())
        audio.start()
        run(1000)
        assertEquals(AudioState.MICROPHONE_OFF, audio.status.state)
        assertEquals(1, capture.opens.size)

        audio.setMicrophoneEnabled(true)
        assertEquals(AudioState.WAKE_LISTENING, audio.status.state)
        assertTrue(setting.enabled)
    }

    @Test
    fun microphoneOffEndsHoldToTalkCapture() {
        talk(true)
        capture.hear(ShortArray(3200))
        run(100)
        val frames = host.sentBinary
        audio.setMicrophoneEnabled(false)
        assertNull(capture.sink)
        run(500)
        talk(false)
        run(100)
        assertEquals(frames, host.sentBinary)
        assertEquals(AudioState.MICROPHONE_OFF, audio.status.state)
        assertNull("nothing reopened the microphone", capture.sink)
    }

    @Test
    fun theGadgetsSpeechPausesWakeListening() {
        NativeCore.transportText(handle, """{"type":"audio.start","stream":7,"rate":24000,"format":"pcm16"}""".toByteArray())
        pump()
        assertTrue(playback.playing)
        assertEquals(AudioState.GADGET_PLAYBACK, audio.status.state)
        assertFalse("its own speech can't reach the detector", capture.hear(wakePhrase))

        playback.playing = false // played out
        run(AudioCoordinator.PLAYBACK_TAIL_MS.toInt() - 100)
        assertNull("still waiting for the room to quieten", capture.sink)
        run(200)
        assertEquals(AudioState.WAKE_LISTENING, audio.status.state)
        assertTrue(capture.hear(wakePhrase))
        awaitDetections(1)
    }

    @Test
    fun aDetectionNeverAnswersAPrompt() {
        NativeCore.transportText(handle, """{"type":"prompt","id":"p1","title":"Approve?","text":"Run the backup now?"}""".toByteArray())
        run(1000)
        val screen = NativeCore.screen(handle).decodeToString()
        capture.hear(wakePhrase)
        awaitDetections(1)
        run(500)
        assertEquals(0, sent("prompt.reply"))
        assertEquals(0, sent("audio.start"))
        assertEquals(0, host.sentBinary)
        assertEquals("Answer the question on screen first", audio.status.feedback)
        assertEquals("the question is still on screen", screen, NativeCore.screen(handle).decodeToString())
    }

    @Test
    fun aHandOffTakesTheMicrophoneAndSpeakerUntilReleased() {
        assertTrue(audio.handOff())
        assertEquals(AudioState.HANDED_OFF, audio.status.state)
        assertNull(capture.sink)
        assertFalse("one owner at a time", audio.handOff())

        talk(true)
        run(700)
        talk(false)
        assertEquals(0, sent("audio.start"))
        NativeCore.transportText(handle, """{"type":"audio.start","stream":7,"rate":24000,"format":"pcm16"}""".toByteArray())
        pump()
        assertFalse("the gadget can't speak over the new owner", playback.playing)

        audio.release(clock)
        run(AudioCoordinator.PLAYBACK_TAIL_MS.toInt() + 20)
        assertEquals(AudioState.WAKE_LISTENING, audio.status.state)

        audio.setMicrophoneEnabled(false)
        assertFalse("Microphone off refuses a hand-off", audio.handOff())
    }

    @Test
    fun withoutWakeListeningHoldToTalkStillWorks() {
        audio.close()
        audio = coordinator(WakeListener { error("no models") })
        audio.start()
        val deadline = System.currentTimeMillis() + 5000
        while (audio.status.state != AudioState.WAKE_UNAVAILABLE && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            run(10)
        }
        assertEquals(AudioState.WAKE_UNAVAILABLE, audio.status.state)
        assertTrue(audio.status.problem.orEmpty(), "did not load" in audio.status.problem.orEmpty())
        assertNull(capture.sink)

        talk(true)
        assertEquals(AudioState.GADGET_CAPTURE, audio.status.state)
        capture.hear(ShortArray(16000))
        run(700)
        talk(false)
        assertTrue(host.sentBinary > 0)
    }

    private companion object {
        const val BLOCK = 320
    }
}

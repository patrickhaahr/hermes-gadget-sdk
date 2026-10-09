package io.github.adolanium.hermesgadget

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The Live call controller as GadgetCore runs it: the production [LiveCall] and
 * [AudioCoordinator], the device core through the JNI bridge, and the real wake
 * pipeline. Frames from the server pass through [LiveCall.inbound] first, as in
 * GadgetCore.onText. The WebRTC media is scripted ([FakeMedia]): what it was asked
 * to do is recorded, and the test plays the voice service by firing its events.
 */
class LiveCallTest {
    private class FakeCapture : Capture {
        var sink: ((ShortArray) -> Unit)? = null
        val opens = mutableListOf<Int>()

        override fun open(rate: Int, sink: (ShortArray) -> Unit): Boolean {
            check(this.sink == null) { "the microphone was opened while another owner had it" }
            this.sink = sink
            opens += rate
            return true
        }

        override fun close() {
            sink = null
        }

        fun hear(pcm: ShortArray): Boolean {
            val s = sink ?: return false
            for (at in pcm.indices step 320) s(pcm.copyOfRange(at, minOf(pcm.size, at + 320)))
            return true
        }
    }

    private inner class FakePlayback : Playback {
        var playing = false
        override fun begin(sampleRate: Int): Boolean {
            playing = true
            return true
        }
        override fun write(samples: ShortArray) {}
        override fun end() {}
        override fun abort() {
            playing = false
        }
        override fun busy() = playing
        override fun setVolume(percent: Int) {}
    }

    /** One call's WebRTC side: records what the controller asked of it. */
    private inner class FakeMedia(val events: CallMediaEvents) : CallMedia {
        var offers = 0
        val answers = mutableListOf<String>()
        val responses = mutableListOf<Pair<String, String>>()
        var closed = false

        override fun createOffer() {
            check(capture.sink == null) { "the call's microphone opened while wake listening still had it" }
            offers++
        }

        override fun setAnswer(sdp: String) {
            check(!closed)
            answers += sdp
        }

        override fun close() {
            closed = true
        }

        override fun respond(delegation: String, text: String): Boolean {
            check(!closed)
            responses += delegation to text
            return true
        }
    }

    private class FakeCue : Cue {
        var plays = 0
        var playing = false
        override fun play() {
            plays++
            playing = true
        }
        override fun stop() {
            playing = false
        }
    }

    private class Setting : MicrophoneSetting {
        override var enabled = true
    }

    private inner class Host : NativeHost {
        val storage = mutableMapOf("server" to "ws://zaza:8765/gadget", "name" to "8T")
        override fun transportConnect(url: ByteArray, subprotocol: ByteArray) {}
        override fun transportSendText(data: ByteArray) = sent.add(data.decodeToString())
        override fun transportSendBinary(data: ByteArray): Boolean {
            binary++
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
    private val cue = FakeCue()
    private val queue = ConcurrentLinkedQueue<Runnable>() // the core thread's queue
    private val sent = mutableListOf<String>() // every text frame to the server, the core's and the call's
    private val media = mutableListOf<FakeMedia>()
    private val statuses = mutableListOf<CallStatus>()
    private var binary = 0
    private var clock = 0L
    private var handle = 0L
    private lateinit var audio: AudioCoordinator
    private lateinit var call: LiveCall

    @Before
    fun create() {
        val models = HostTfliteModel.models()
        audio = AudioCoordinator(capture, playback, WakeListener(queueChunks = 1000) { WakeDetector(models) }, setting, queue::add,
            toCore = { samples -> NativeCore.micSamples(handle, samples, samples.size) }, onStatus = {},
            startRequest = { NativeCore.startWakeRequest(handle).decodeToString() },
            discardRequest = { NativeCore.discardWakeRequest(handle) })
        call = LiveCall(audio, { events -> FakeMedia(events).also { media += it } }, cue, sent::add, queue::add, { clock },
            onStatus = { statuses += it })
        handle = NativeCore.create(host, 360, 800, hasMic = true, hasSpeaker = true, micRate = 16000,
            speakerRate = 24000, board = "android".toByteArray(), firmware = "0.2.0".toByteArray(),
            defaultName = "Android Gadget".toByteArray(), talkLabel = null, cancelLabel = null, touch = true)
        NativeCore.begin(handle)
        audio.start()
    }

    @After
    fun destroy() {
        call.end()
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
            call.tick()
            pump()
        }
    }

    /** A frame from the server, delivered as GadgetCore.onText does. */
    private fun server(json: String) {
        if (call.inbound(json)) NativeCore.transportText(handle, json.toByteArray())
        pump()
    }

    private fun online(calls: Boolean = true, paired: Boolean = true, tasks: Boolean = true) {
        NativeCore.network(handle, true, "Wi-Fi".toByteArray())
        run(2000)
        call.transportOpened()
        NativeCore.transportOpen(handle)
        run(50)
        val nonce = Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })
        server("""{"type":"challenge","nonce":"$nonce","enrolled":false}""")
        run(50)
        val offer = if (calls) ""","calls":["live"]""" else ""
        server("""{"type":"welcome","session":"s1","paired":$paired,"heartbeat_s":20,"server":"hermes","proto":1,"call_tasks":$tasks$offer}""")
        run(600)
        assertEquals(AudioState.WAKE_LISTENING, audio.status.state)
    }

    private fun frames(type: String) = sent.filter { "\"type\":\"$type\"" in it }.map(::JSONObject)

    private fun talk(pressed: Boolean) {
        NativeCore.button(handle, NativeCore.BUTTON_TALK, pressed)
        pump()
    }

    /** Start call through to the answer; returns the call's id and media. */
    private fun answeredCall(): Pair<String, FakeMedia> {
        call.start()
        val m = media.last()
        m.events.offer("v=0 offer")
        pump()
        val id = frames("call.start").last().getString("id")
        server("""{"type":"call.answer","id":"$id","answer":"v=0 answer"}""")
        return id to m
    }

    @Test
    fun aDelegationUsesTheGadgetConnectionAndOnlyItsFinalResultIsSpokenOnce() {
        online()
        val (id, m) = answeredCall()
        m.events.ready()
        pump()
        m.events.delegation("d1", "Check the timer")
        m.events.delegation("d1", "Check the timer")
        pump()
        val request = frames("call.task").single()
        assertEquals(id, request.getString("id"))
        assertEquals("d1", request.getString("delegation"))
        assertEquals("Check the timer", request.getString("text"))
        assertFalse(request.has("profile") || request.has("device_id"))
        server("""{"type":"call.task.status","id":"$id","delegation":"d1","state":"accepted","turn":"s1:t1"}""")
        assertTrue(m.responses.isEmpty())
        server("""{"type":"call.task.status","id":"$id","delegation":"d1","state":"completed","turn":"other","text":"wrong result"}""")
        assertTrue(m.responses.isEmpty())
        repeat(2) {
            server("""{"type":"call.task.status","id":"$id","delegation":"d1","state":"completed","turn":"s1:t1","text":"The timer has two minutes left."}""")
        }
        assertEquals("d1", m.responses.single().first)
        assertTrue(m.responses.single().second.contains("two minutes left"))
        assertEquals(CallState.ACTIVE, call.status.state)
        assertEquals(0, binary)
    }

    @Test
    fun busyFeedbackDoesNotQueueAndOldCallResultsNeverReachANewCall() {
        online()
        val (id, m) = answeredCall()
        m.events.ready()
        pump()
        m.events.delegation("d1", "Check the timer")
        pump()
        server("""{"type":"call.task.status","id":"$id","delegation":"d1","state":"busy","text":"Please wait, then ask again."}""")
        assertEquals("Please wait, then ask again.", m.responses.single().second)
        run(1000)
        assertEquals(1, frames("call.task").size)
        call.end()
        run(600)
        val (_, next) = answeredCall()
        next.events.ready()
        pump()
        m.events.delegation("late", "Never submit this")
        server("""{"type":"call.task.status","id":"$id","delegation":"d1","state":"completed","text":"Old result"}""")
        pump()
        assertTrue(next.responses.isEmpty())
        assertEquals(1, frames("call.task").size)
        assertEquals(0, frames("cancel").size)
        assertEquals(0, frames("session.new").size)
    }

    @Test
    fun anOnScreenApprovalCanBeAnsweredWhileTheCallOwnsTheMicrophone() {
        online()
        val (_, m) = answeredCall()
        m.events.ready()
        pump()
        server("""{"type":"prompt","id":"p1","title":"Allow command?","text":"test command"}""")
        run(700)
        talk(true)
        talk(false)
        val answer = frames("prompt.reply").single()
        assertEquals("p1", answer.getString("id"))
        assertEquals("yes", answer.getString("answer"))
        assertEquals(CallState.ACTIVE, call.status.state)
        assertNull(capture.sink)
        assertEquals(0, binary)
    }

    @Test
    fun anOlderHostCanConverseButGetsNoTaskSubmission() {
        online(tasks = false)
        val (_, m) = answeredCall()
        m.events.ready()
        pump()
        m.events.delegation("d1", "Check the timer")
        pump()
        assertTrue(frames("call.task").isEmpty())
        assertTrue(m.responses.single().second.contains("update the gadget plugin"))
        assertEquals(CallState.ACTIVE, call.status.state)
    }

    @Test
    fun startCallTakesTheMicrophoneThenOffersOverThePairedConnection() {
        online()
        assertTrue("wake listening had the microphone", capture.hear(ShortArray(16000)))
        clock += 1000
        call.start()

        assertEquals(AudioState.HANDED_OFF, audio.status.state)
        assertNull("wake listening let go of the microphone and what it heard", capture.sink)
        assertFalse(capture.hear(ShortArray(16000)))
        assertEquals(CallState.STARTING, call.status.state)
        assertEquals(1, media.single().offers)

        media.single().events.offer("v=0 offer")
        pump()
        val start = frames("call.start").single()
        assertEquals("v=0 offer", start.getString("offer"))
        assertEquals("en", start.getString("language"))
        assertFalse("identity is the connection's, not a field", start.has("profile") || start.has("device_id"))
        assertEquals("no gadget audio went out", 0, binary)
        assertEquals(0, frames("audio.start").size)
    }

    @Test
    fun theReadyCuePlaysOnlyOnceTheServiceStartedTheSession() {
        online()
        clock = 10_000
        val (_, m) = answeredCall()
        assertEquals(listOf("v=0 answer"), m.answers)
        assertEquals("an answer alone is not a usable call", 0, cue.plays)
        assertEquals(CallState.STARTING, call.status.state)

        run(1500)
        m.events.ready()
        pump()
        assertEquals(1, cue.plays)
        assertEquals(CallState.ACTIVE, call.status.state)
        assertEquals(1500L, call.status.readyMs)
        assertEquals(CallState.ACTIVE, statuses.last().state)
    }

    @Test
    fun endCallReleasesTheCallButKeepsTheGadget() {
        online()
        val (id, m) = answeredCall()
        m.events.ready()
        pump()

        // While the call has the audio, the gadget neither records nor speaks.
        talk(true)
        run(700)
        talk(false)
        assertEquals(0, frames("audio.start").size)
        server("""{"type":"audio.start","stream":7,"rate":24000,"format":"pcm16"}""")
        assertFalse(playback.playing)

        call.end()
        assertEquals(id, frames("call.stop").single().getString("id"))
        assertTrue(m.closed)
        assertFalse(cue.playing)
        assertEquals(CallState.IDLE, call.status.state)
        assertEquals("Call ended", call.status.feedback)
        assertNull("no call is available to stop twice", call.status.unavailable)

        run(AudioCoordinator.PLAYBACK_TAIL_MS.toInt() + 20)
        assertEquals("wake listening resumed", AudioState.WAKE_LISTENING, audio.status.state)
        server("""{"type":"call.ended","id":"$id","reason":"hangup"}""")
        talk(true)
        run(100)
        assertEquals("hold-to-talk works again", 1, frames("audio.start").size)
    }

    @Test
    fun aCallNotReadyIn20SecondsFailsAndALateAnswerIsIgnored() {
        online()
        call.start()
        val m = media.single()
        m.events.offer("v=0 offer")
        pump()
        val id = frames("call.start").single().getString("id")

        run(LiveCall.START_TIMEOUT_MS.toInt() - 100)
        assertEquals(CallState.STARTING, call.status.state)
        run(200)
        assertEquals(CallState.IDLE, call.status.state)
        assertEquals("Live call failed: no answer within 20 seconds", call.status.feedback)
        assertEquals(id, frames("call.stop").single().getString("id"))
        assertTrue(m.closed)

        server("""{"type":"call.answer","id":"$id","answer":"v=0 late"}""")
        m.events.ready()
        pump()
        assertEquals(emptyList<String>(), m.answers)
        assertEquals(0, cue.plays)
        assertEquals("nothing started again by itself", 1, media.size)
        run(AudioCoordinator.PLAYBACK_TAIL_MS.toInt() + 20)
        assertEquals(AudioState.WAKE_LISTENING, audio.status.state)
    }

    @Test
    fun aRefusedCallReportsWhyAndHangsUpNothing() {
        online()
        call.start()
        media.single().events.offer("v=0 offer")
        pump()
        val id = frames("call.start").single().getString("id")
        server("""{"type":"call.error","id":"$id","code":"live_sin_quota","message":"the ChatGPT plan reached its limit"}""")
        assertEquals("Live call failed: the ChatGPT plan reached its limit", call.status.feedback)
        assertEquals("the server has nothing to stop", 0, frames("call.stop").size)
        assertTrue(media.single().closed)
        assertEquals(CallState.IDLE, call.status.state)
    }

    @Test
    fun aDroppedCallHangsUpAndWaitsForTheNextStartCall() {
        online()
        val (id, m) = answeredCall()
        m.events.ready()
        pump()
        m.events.failed("the connection to the voice service failed")
        pump()
        assertEquals("Call dropped: the connection to the voice service failed", call.status.feedback)
        assertEquals(id, frames("call.stop").single().getString("id"))
        run(5000)
        assertEquals("no reconnection", 1, media.size)
        assertEquals(AudioState.WAKE_LISTENING, audio.status.state)
    }

    @Test
    fun eventsFromAnEndedCallDoNotReachTheNextOne() {
        online()
        val (_, first) = answeredCall()
        call.end()
        run(AudioCoordinator.PLAYBACK_TAIL_MS.toInt() + 20)
        call.start()
        first.events.offer("v=0 stale")
        first.events.ready()
        first.events.failed("stale")
        pump()
        assertEquals(CallState.STARTING, call.status.state)
        assertEquals(0, cue.plays)
        assertEquals("only the first call's offer went out", 1, frames("call.start").size)
        assertFalse(media.last().closed)
    }

    @Test
    fun startCallNeedsAPairedHostThatOffersCalls() {
        online(calls = false)
        assertEquals("This Hermes host does not offer Live calls", call.status.unavailable)
        call.start()
        assertEquals(0, media.size)
        assertEquals(AudioState.WAKE_LISTENING, audio.status.state)
        assertEquals("This Hermes host does not offer Live calls", call.status.feedback)

        server("""{"type":"welcome","session":"s2","paired":false,"heartbeat_s":20,"server":"hermes","proto":1,"calls":["live"]}""")
        call.start()
        assertEquals("This phone is not paired with Hermes", call.status.feedback)
        server("""{"type":"paired"}""")
        assertNull(call.status.unavailable)

        call.transportClosed()
        call.start()
        assertEquals("Hermes is not connected", call.status.feedback)
        assertEquals(0, media.size)
        assertEquals(0, frames("call.start").size)
    }

    @Test
    fun startCallWaitsForTheMicrophoneAndHoldToTalk() {
        online()
        talk(true)
        call.start()
        assertEquals("Finish the recording first", call.status.feedback)
        talk(false)
        run(100)

        call.setMicrophoneEnabled(false)
        call.start()
        assertEquals("Turn the microphone on first", call.status.feedback)
        assertEquals(0, media.size)
    }

    @Test
    fun microphoneOffEndsTheCallAndKeepsWakeListeningOff() {
        online()
        val (id, m) = answeredCall()
        m.events.ready()
        pump()
        call.setMicrophoneEnabled(false)
        assertTrue(m.closed)
        assertEquals(id, frames("call.stop").single().getString("id"))
        assertEquals("Microphone off", call.status.feedback)
        run(2000)
        assertEquals(AudioState.MICROPHONE_OFF, audio.status.state)
        assertNull(capture.sink)
        assertFalse(setting.enabled)
    }

    @Test
    fun losingTheConnectionOrThePairingEndsTheCall() {
        online()
        val (_, m) = answeredCall()
        m.events.ready()
        pump()
        call.transportClosed()
        assertTrue(m.closed)
        assertEquals("Lost the connection to Hermes", call.status.feedback)
        assertEquals("nothing to send it on", 0, frames("call.stop").size)

        online()
        val (id, second) = answeredCall()
        server("""{"type":"unpaired"}""")
        assertTrue(second.closed)
        assertEquals("This phone is no longer paired", call.status.feedback)
        server("""{"type":"call.ended","id":"$id","reason":"unpaired"}""")
        assertNotNull(call.status.unavailable)
    }

    @Test
    fun callFramesStayOutOfTheCore() {
        assertFalse(call.inbound("""{"type":"call.answer","id":"x","answer":"v=0"}"""))
        assertFalse(call.inbound("""{"type":"call.ended","id":"x","reason":"hangup"}"""))
        assertTrue(call.inbound("""{"type":"reply","text":"hi"}"""))
        assertTrue(call.inbound("not json"))
    }
}

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
        var closeDelayMs = 0L

        override fun createOffer() {
            check(capture.sink == null) { "the call's microphone opened while wake listening still had it" }
            offers++
        }

        override fun setAnswer(sdp: String) {
            check(!closed)
            answers += sdp
        }

        override fun close() {
            clock += closeDelayMs
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
    private var voiceMode = VoiceMode.HERMES
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
            discardRequest = { NativeCore.discardWakeRequest(handle) },
            voiceMode = { voiceMode },
            startLiveCall = { call.start(); call.status.feedback ?: "Starting Live call" })
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

    private fun delegation(id: String, text: String) = JSONObject().put("type", "delegation.created")
        .put("item", JSONObject().put("id", id).put("content", org.json.JSONArray().put(JSONObject().put("text", text)))).toString()

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

    private fun wake() {
        val before = audio.status.detections
        assertTrue(capture.hear(HostFixtures.pcm(WakeFixtures.UNRELATED) + HostFixtures.pcm(WakeFixtures.POSITIVE_LJSPEECH)))
        val deadline = System.currentTimeMillis() + 20_000
        while (audio.status.detections == before && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            pump()
        }
        assertEquals(before + 1, audio.status.detections)
    }

    @Test
    fun repeatedWakesUseTheCallPathAndRearmAfterHangupOrFailureWithoutUploadingPreroll() {
        online()
        voiceMode = VoiceMode.LIVE
        server("""{"type":"prompt","id":"p1","title":"Allow command?","text":"test command"}""")
        for (ending in listOf("goodbye", "startup failure", "button with task")) {
            wake()
            assertEquals(CallState.STARTING, call.status.state)
            assertEquals(AudioState.HANDED_OFF, audio.status.state)
            assertNull(capture.sink)
            assertEquals(0, binary)
            assertEquals(0, frames("audio.start").size)
            assertEquals("wake never approves a command", 0, frames("prompt.reply").size)
            val m = media.last()
            m.events.offer("v=0 offer")
            pump()
            val id = frames("call.start").last().getString("id")
            assertEquals("en", frames("call.start").last().getString("language"))
            if (ending == "startup failure") {
                server("""{"type":"call.error","id":"$id","message":"test startup failure"}""")
                m.events.message("""{"type":"session.started"}""") // late startup cannot resurrect it
                pump()
            } else {
                server("""{"type":"call.answer","id":"$id","answer":"v=0 answer"}""")
                m.events.message("""{"type":"session.started"}""")
                pump()
                assertEquals(CallState.ACTIVE, call.status.state)
                if (ending == "goodbye") {
                    transcript(m, "Goodbye Hermes.")
                    run(3500)
                } else {
                    m.events.message(delegation("d1", "Check the timer"))
                    pump()
                    server("""{"type":"call.task.status","id":"$id","delegation":"d1","state":"accepted","turn":"t1"}""")
                    call.end()
                    server("""{"type":"call.task.status","id":"$id","delegation":"d1","state":"completed","turn":"t1","text":"Old result"}""")
                    assertTrue(m.responses.isEmpty())
                }
            }
            assertTrue(m.closed)
            assertEquals(CallState.IDLE, call.status.state)
            assertNull("rearm waits for media and cue teardown", capture.sink)
            run(600)
            assertEquals(AudioState.WAKE_LISTENING, audio.status.state)
            run(1000)
        }
        assertEquals(3, frames("call.start").size)
        assertEquals(2, cue.plays)
        assertEquals(0, frames("cancel").size)
        assertEquals(0, binary)
    }

    @Test
    fun unavailableLiveWakeNeverFallsBackAndHermesModeStillRecordsARequest() {
        online(calls = false)
        voiceMode = VoiceMode.LIVE
        wake()
        assertEquals("This Hermes host does not offer Live calls", call.status.feedback)
        assertEquals(AudioState.WAKE_LISTENING, audio.status.state)
        assertTrue(media.isEmpty())
        assertEquals(0, frames("audio.start").size)
        voiceMode = VoiceMode.HERMES
        wake()
        assertEquals(AudioState.GADGET_CAPTURE, audio.status.state)
        assertTrue(media.isEmpty())
        assertEquals(0, binary)
        call.setMicrophoneEnabled(false)
        run(1000)
        assertNull(capture.sink)
        assertEquals(AudioState.MICROPHONE_OFF, audio.status.state)
    }

    private fun transcript(media: FakeMedia, text: String, role: String = "user", delta: Boolean = true) {
        val event = if (delta) JSONObject().put("type", if (role == "user") "input_transcript.added" else "output_transcript.added")
            .put("item", JSONObject().put("text", text))
        else JSONObject().put("type", "turn.done").put("turn", JSONObject().put("role", role).put("transcript", text))
        media.events.message(event.toString())
        pump()
    }

    @Test
    fun goodbyeWaitsForTheWholeUserUtteranceAndDoesNotNeedTurnDone() {
        online()
        val (id, m) = answeredCall()
        m.events.message("""{"type":"session.started"}""")
        pump()
        for ((role, text) in listOf(
            "assistant" to "Goodbye Hermes.",
            "user" to "Say goodbye Hermes to the visitor.",
            "user" to "The phrase is \"Goodbye Hermes\".",
            "user" to "\"Goodbye Hermes\"",
        )) {
            transcript(m, text, role)
            run(4000)
            assertEquals("$role: $text", CallState.ACTIVE, call.status.state)
        }
        transcript(m, "Goodbye Hermes")
        run(500)
        transcript(m, ", what does that phrase mean?")
        run(4000)
        assertEquals(CallState.ACTIVE, call.status.state)

        transcript(m, "Good")
        run(100)
        transcript(m, "bye")
        transcript(m, "Goodbye", delta = false) // partial bookkeeping must not replace the deltas
        transcript(m, " ")
        transcript(m, "Hermes!")
        run(1000)
        assertEquals(CallState.ACTIVE, call.status.state)
        run(3000)
        assertEquals(CallState.IDLE, call.status.state)
        assertEquals("Call ended", call.status.feedback)
        assertTrue(m.closed)
        assertEquals(id, frames("call.stop").single().getString("id"))
        assertEquals(AudioState.WAKE_LISTENING, audio.status.state)
        assertEquals(0, frames("cancel").size)
    }

    @Test
    fun aFinalUserTranscriptCanEndTheCallAndStaleSpeechCannotEndTheNextOne() {
        online()
        val (_, old) = answeredCall()
        old.events.message("""{"type":"session.started"}""")
        pump()
        transcript(old, "GOODBYE, HERMES.", delta = false)
        run(4000)
        assertEquals(CallState.IDLE, call.status.state)
        val (_, next) = answeredCall()
        next.events.message("""{"type":"session.started"}""")
        pump()
        transcript(old, "Goodbye Hermes.")
        run(4000)
        assertEquals(CallState.ACTIVE, call.status.state)
        assertFalse(next.closed)
    }

    @Test
    fun idleStartsAtReadinessAndBothSpeakersKeepTheCallAlive() {
        online()
        val (id, m) = answeredCall()
        run(15_000)
        m.events.message("""{"type":"session.started"}""")
        pump()
        run(59_000)
        assertEquals(CallState.ACTIVE, call.status.state)
        transcript(m, "Still here")
        run(59_000)
        assertEquals(CallState.ACTIVE, call.status.state)
        transcript(m, "So am I", "assistant")
        run(59_000)
        assertEquals(CallState.ACTIVE, call.status.state)
        run(1000)
        assertEquals(CallState.IDLE, call.status.state)
        assertEquals("Call ended after 60 seconds without speech", call.status.feedback)
        assertTrue(m.closed)
        assertEquals(id, frames("call.stop").single().getString("id"))
        run(600)
        assertEquals(AudioState.WAKE_LISTENING, audio.status.state)
    }

    @Test
    fun pendingWorkPausesIdleUntilCompletionButGoodbyeCanStillHangUp() {
        online()
        for (explicit in listOf(false, true)) {
            val (id, m) = answeredCall()
            m.events.message("""{"type":"session.started"}""")
            pump()
            m.events.message(delegation("d1", "Check the timer"))
            pump()
            run(61_000) // even before acceptance, the request is unresolved
            assertEquals(CallState.ACTIVE, call.status.state)
            server("""{"type":"call.task.status","id":"$id","delegation":"d1","state":"accepted","turn":"$id:task"}""")
            run(61_000)
            assertEquals(CallState.ACTIVE, call.status.state)
            if (explicit) {
                transcript(m, "Goodbye Hermes.")
                run(4000)
                server("""{"type":"call.task.status","id":"$id","delegation":"d1","state":"completed","turn":"$id:task","text":"Old result"}""")
                assertTrue(m.responses.isEmpty())
            } else {
                server("""{"type":"call.task.status","id":"$id","delegation":"d1","state":"completed","turn":"$id:task","text":"The timer is ready"}""")
                run(59_000)
                assertEquals("completion gives the voice time to speak", CallState.ACTIVE, call.status.state)
                run(1000)
            }
            assertEquals(CallState.IDLE, call.status.state)
            assertTrue(m.closed)
            run(600)
        }
        assertEquals(0, frames("cancel").size)
        assertEquals(0, frames("session.new").size)
    }

    @Test
    fun aDelegationUsesTheGadgetConnectionAndOnlyItsFinalResultIsSpokenOnce() {
        online()
        val (id, m) = answeredCall()
        m.events.message("""{"type":"session.started"}""")
        pump()
        m.events.message(delegation("d1", "Check the timer"))
        m.events.message(delegation("d1", "Check the timer"))
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
        for (teardown in listOf("hangup", "microphone off", "media failure", "transport loss")) {
            val before = frames("call.task").size
            val (id, m) = answeredCall()
            m.events.message("""{"type":"session.started"}""")
            pump()
            m.events.message(delegation("d1", "Check the timer"))
            pump()
            server("""{"type":"call.task.status","id":"$id","delegation":"d1","state":"accepted","turn":"$id:task"}""")
            assertTrue(m.responses.isEmpty())
            when (teardown) {
                "hangup" -> call.end()
                "microphone off" -> call.setMicrophoneEnabled(false)
                "media failure" -> { m.events.failed("lost media"); pump() }
                "transport loss" -> {
                    call.transportClosed()
                    NativeCore.transportClosed(handle, "lost link".toByteArray())
                }
            }
            assertTrue("$teardown released WebRTC", m.closed)
            run(600)
            assertEquals(CallState.IDLE, call.status.state)
            assertEquals(before + 1, frames("call.task").size)
            assertTrue(m.responses.isEmpty())
            if (teardown == "microphone off") {
                assertEquals(AudioState.MICROPHONE_OFF, audio.status.state)
                assertNull(capture.sink)
                call.setMicrophoneEnabled(true)
                run(600)
            }
            if (teardown == "transport loss") online()
            val (newId, next) = answeredCall()
            next.events.message("""{"type":"session.started"}""")
            pump()
            // The new voice session can reuse an item id. Old acknowledgments,
            // duplicate completions and delayed native events still belong to m.
            next.events.message(delegation("d1", "Another task"))
            pump()
            m.events.message(delegation("late", "Never submit this"))
            server("""{"type":"call.task.status","id":"$id","delegation":"d1","state":"accepted","turn":"$id:task"}""")
            repeat(2) {
                server("""{"type":"call.task.status","id":"$id","delegation":"d1","state":"completed","turn":"$id:task","text":"Old result"}""")
            }
            assertTrue(next.responses.isEmpty())
            server("""{"type":"call.task.status","id":"$newId","delegation":"d1","state":"busy","text":"Please wait, then ask again."}""")
            assertEquals("Please wait, then ask again.", next.responses.single().second)
            run(1000)
            assertEquals(before + 2, frames("call.task").size)
            assertEquals(CallState.ACTIVE, call.status.state)
            call.end()
            run(600)
        }
        assertEquals(0, frames("cancel").size)
        assertEquals(0, frames("session.new").size)
        assertEquals(0, binary)
    }

    @Test
    fun aTaskOutlivingItsCallAlsoPausesANewCallsIdleTimer() {
        online()
        val (oldId, old) = answeredCall()
        old.events.message("""{"type":"session.started"}""")
        pump()
        old.events.message(delegation("d1", "Check the timer"))
        pump()
        server("""{"type":"call.task.status","id":"$oldId","delegation":"d1","state":"accepted","turn":"old-task"}""")
        call.end()
        run(600)
        val (_, next) = answeredCall()
        next.events.message("""{"type":"session.started"}""")
        pump()
        run(61_000)
        assertEquals("the older task is still running", CallState.ACTIVE, call.status.state)
        server("""{"type":"turn.end","turn":"unrelated-task"}""")
        run(61_000)
        assertEquals(CallState.ACTIVE, call.status.state)
        server("""{"type":"call.task.status","id":"$oldId","delegation":"d1","state":"completed","turn":"old-task","text":"Old result"}""")
        server("""{"type":"turn.end","turn":"old-task"}""")
        assertTrue(next.responses.isEmpty())
        run(59_000)
        assertEquals(CallState.ACTIVE, call.status.state)
        run(1000)
        assertEquals(CallState.IDLE, call.status.state)
        assertTrue(next.closed)
    }

    @Test
    fun anOnScreenApprovalCanBeAnsweredWhileTheCallOwnsTheMicrophone() {
        online()
        val (_, m) = answeredCall()
        m.events.message("""{"type":"session.started"}""")
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
        m.events.message("""{"type":"session.started"}""")
        pump()
        m.events.message(delegation("d1", "Check the timer"))
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
        m.events.message("""{"type":"session.started"}""")
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
        m.events.message("""{"type":"session.started"}""")
        pump()

        // While the call has the audio, the gadget neither records nor speaks.
        talk(true)
        run(700)
        talk(false)
        assertEquals(0, frames("audio.start").size)
        server("""{"type":"audio.start","stream":7,"rate":24000,"format":"pcm16"}""")
        assertFalse(playback.playing)

        m.closeDelayMs = 750 // WebRTC teardown can block longer than the rearm tail
        call.end()
        assertEquals(id, frames("call.stop").single().getString("id"))
        assertTrue(m.closed)
        assertFalse(cue.playing)
        assertEquals(CallState.IDLE, call.status.state)
        assertEquals("Call ended", call.status.feedback)
        assertNull("no call is available to stop twice", call.status.unavailable)

        run(400)
        assertNull("the full tail starts after slow media teardown", capture.sink)
        run(120)
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
        m.events.message("""{"type":"session.started"}""")
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
        m.events.message("""{"type":"session.started"}""")
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
        first.events.message("""{"type":"session.started"}""")
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
        m.events.message("""{"type":"session.started"}""")
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
        m.events.message("""{"type":"session.started"}""")
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

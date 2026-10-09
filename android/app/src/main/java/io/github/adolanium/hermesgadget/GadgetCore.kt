package io.github.adolanium.hermesgadget

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.nio.ByteOrder
import java.security.SecureRandom

/** What the screen shows: the core's framebuffer, copied out on each flush. */
class Frame(val width: Int, val height: Int) {
    val pixels = ShortArray(width * height)
    @Volatile var backlight = 100
}

/**
 * Hosts the production device core on one thread, as the architecture requires:
 * ticks, transport events, microphone blocks, touches, every native callback and
 * the [AudioCoordinator] run on [thread]. Everything else talks to it through [post].
 */
class GadgetCore(
    private val store: DeviceStore,
    private val firmware: String,
    val frame: Frame,
    microphone: MicrophoneSetting,
    wake: WakeEngine?,
    private val onFrame: () -> Unit,
    private val onAudio: (AudioStatus) -> Unit,
    private val onStopped: (String) -> Unit,
    private val voiceMode: () -> VoiceMode = { VoiceMode.HERMES },
    callMedia: CallMediaFactory = CallMediaFactory { events -> events.failed("calls are not available"); null },
    cue: Cue = object : Cue {
        override fun play() {}
        override fun stop() {}
    },
    private val onCall: (CallStatus) -> Unit = {},
) : NativeHost, TransportEvents {
    private val thread = HandlerThread("gadget-core").apply { start() }
    private val handler = Handler(thread.looper)
    private val random = SecureRandom()
    private val started = SystemClock.elapsedRealtime()
    private val transport = WsTransport(::post, this)
    private val speaker = Speaker()
    private var handle = 0L
    private var detections = 0 // core thread only
    private val audio = AudioCoordinator(Microphone(), speaker, wake, microphone, ::post,
        toCore = { samples -> if (handle != 0L) NativeCore.micSamples(handle, samples, samples.size) },
        onStatus = ::audioChanged,
        startRequest = { if (handle != 0L) NativeCore.startWakeRequest(handle).decodeToString() else "The gadget is not running" },
        discardRequest = { if (handle != 0L) NativeCore.discardWakeRequest(handle) },
        voiceMode = voiceMode,
        startLiveCall = { call.start(); call.status.feedback ?: "Starting Live call" })
    private val call: LiveCall = LiveCall(audio, callMedia, cue, transport::sendText, ::post, ::nowMs,
        onStatus = { status ->
            callStatus = status
            onCall(status)
        },
        log = { Log.i(CALL_TAG, it) })

    /** The microphone's owner, as last reported on the core thread. */
    @Volatile var audioStatus = audio.status
        private set

    /** The Live call's state, as last reported on the core thread. */
    @Volatile var callStatus: CallStatus = call.status
        private set

    private val tick = object : Runnable {
        override fun run() {
            if (handle == 0L) return
            NativeCore.tick(handle)
            audio.tick(nowMs())
            call.tick()
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun start() = post {
        check(NativeCore.abiVersion() == NativeCore.ABI_VERSION) { "libhgjni has the wrong ABI version" }
        handle = NativeCore.create(
            this, frame.width, frame.height, hasMic = true, hasSpeaker = true, micRate = MIC_RATE,
            speakerRate = SPEAKER_RATE, board = BOARD.toByteArray(), firmware = firmware.toByteArray(),
            defaultName = GadgetConfig.DEFAULT_NAME.toByteArray(), talkLabel = null, cancelLabel = null, touch = true,
        )
        check(handle != 0L) { "the device core did not start" }
        NativeCore.begin(handle)
        audio.start()
        handler.post(tick)
    }

    /** Stops the core and its drivers and waits briefly for it; the instance can't be restarted. */
    fun stop() {
        post {
            handler.removeCallbacks(tick)
            call.end("The gadget stopped")
            audio.close()
            speaker.release()
            transport.shutdown()
            if (handle != 0L) NativeCore.destroy(handle)
            handle = 0L
        }
        thread.quitSafely()
        if (Thread.currentThread() != thread) thread.join(STOP_WAIT_MS)
    }

    fun post(block: Runnable) {
        handler.post(block)
    }

    // -- events from Android ------------------------------------------------------------

    fun network(up: Boolean, detail: String) = post { if (handle != 0L) NativeCore.network(handle, up, detail.toByteArray()) }

    fun touch(touching: Boolean, x: Int, y: Int) = post { if (handle != 0L) NativeCore.touch(handle, touching, x, y) }

    fun button(button: Int, pressed: Boolean) = post { if (handle != 0L) NativeCore.button(handle, button, pressed) }

    /** Microphone off stops all capture, wake listening and any call included, until it is turned on again. */
    fun setMicrophoneEnabled(enabled: Boolean) = post { call.setMicrophoneEnabled(enabled) }

    /** Start call: a subscription Live call over the paired connection. */
    fun startCall() = post { call.start() }

    /** End call: hangs up; the gadget connection stays. */
    fun endCall() = post { call.end() }

    /** Runs a serial-console command (such as `set screen_timeout 60`) on the core thread. */
    fun console(line: String, callback: (String) -> Unit) = post {
        callback(if (handle != 0L) NativeCore.console(handle, line.toByteArray()).decodeToString() else "@error not running")
    }

    /** The core's status JSON, read on the core thread. */
    fun status(callback: (String) -> Unit) = post {
        callback(if (handle != 0L) NativeCore.status(handle).decodeToString() else "{}")
    }

    // -- TransportEvents (core thread) ----------------------------------------------------

    override fun onOpen() {
        call.transportOpened()
        if (handle != 0L) NativeCore.transportOpen(handle)
    }

    override fun onText(text: String) {
        if (call.inbound(text) && handle != 0L) NativeCore.transportText(handle, text.toByteArray())
    }

    override fun onBinary(data: ByteArray) {
        if (handle != 0L) NativeCore.transportBinary(handle, data)
    }

    override fun onClosed(reason: String) {
        call.transportClosed()
        if (handle != 0L) NativeCore.transportClosed(handle, reason.toByteArray())
    }

    // -- NativeHost (core thread) ---------------------------------------------------------

    // A socket the core replaces or closes reports nothing more, so the call learns it here.
    override fun transportConnect(url: ByteArray, subprotocol: ByteArray) {
        call.transportClosed()
        transport.connect(url.decodeToString(), subprotocol.decodeToString())
    }

    override fun transportSendText(data: ByteArray) = transport.sendText(data.decodeToString())

    override fun transportSendBinary(data: ByteArray) = transport.sendBinary(data)

    override fun transportClose() {
        call.transportClosed()
        transport.close()
    }

    override fun displayFlush(y0: Int, y1: Int) {
        val fb = NativeCore.framebuffer(handle) ?: return
        val rows = fb.order(ByteOrder.nativeOrder()).asShortBuffer()
        synchronized(frame) {
            rows.position(y0 * frame.width)
            rows.get(frame.pixels, y0 * frame.width, (y1 - y0) * frame.width)
        }
        onFrame()
    }

    override fun displayBacklight(percent: Int) {
        frame.backlight = percent
        onFrame()
    }

    override fun micStart(rate: Int) = audio.micStart(rate)

    override fun micStop() = audio.micStop()

    override fun speakerBegin(rate: Int) = audio.speakerBegin(rate)

    override fun speakerWrite(samples: ShortArray) = audio.speakerWrite(samples)

    override fun speakerEnd() = audio.speakerEnd()

    override fun speakerAbort() = audio.speakerAbort()

    override fun speakerBusy() = audio.speakerBusy()

    override fun speakerVolume(percent: Int) = audio.speakerVolume(percent)

    override fun storageGet(key: ByteArray): ByteArray? = store.get(key.decodeToString())?.toByteArray()

    override fun storageSet(key: ByteArray, value: ByteArray) = persist { store.set(key.decodeToString(), value.decodeToString()) }

    override fun storageErase(key: ByteArray) = persist { store.erase(key.decodeToString()) }

    override fun nowMs(): Long = SystemClock.elapsedRealtime() - started

    override fun randomBytes(count: Int): ByteArray = ByteArray(count).also(random::nextBytes)

    override fun log(level: Int, message: ByteArray) {
        // The core logs connection state and action failures, never device keys.
        Log.println(LOG_PRIORITY.getOrElse(level) { Log.INFO }, TAG, message.decodeToString())
    }

    private fun audioChanged(status: AudioStatus) {
        if (status.detections != detections) {
            detections = status.detections
            Log.i(WAKE_TAG, "heard \"Hey Hermes\" (score %.3f, detection %d)".format(status.lastDetectionScore ?: Float.NaN, detections))
        } else {
            Log.i(WAKE_TAG, "microphone: ${status.state}" + (status.problem?.let { " ($it)" } ?: ""))
        }
        audioStatus = status
        onAudio(status)
    }

    // Like the Linux client: a device that can't save its state (such as a new key) stops
    // rather than run on with an identity it would lose at the next start.
    private fun persist(write: () -> Unit) {
        try {
            write()
        } catch (e: IOException) {
            Log.e(TAG, "device state could not be saved", e)
            handler.removeCallbacks(tick)
            handler.post { onStopped("Device state could not be saved: ${e.message}") }
        }
    }

    companion object {
        const val BOARD = "android"
        const val MIC_RATE = 16000
        const val SPEAKER_RATE = 24000
        private const val TICK_MS = 10L
        private const val STOP_WAIT_MS = 2000L
        private const val TAG = "HermesGadget"
        private const val WAKE_TAG = "HermesWake"
        private const val CALL_TAG = "HermesCall"
        private val LOG_PRIORITY = intArrayOf(Log.DEBUG, Log.INFO, Log.WARN, Log.ERROR)
    }
}

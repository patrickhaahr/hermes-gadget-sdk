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
 * ticks, transport events, microphone blocks, touches and every native callback
 * run on [thread]. Everything else talks to it through [post].
 */
class GadgetCore(
    private val store: DeviceStore,
    private val firmware: String,
    val frame: Frame,
    private val onFrame: () -> Unit,
    private val onStopped: (String) -> Unit,
) : NativeHost, TransportEvents {
    private val thread = HandlerThread("gadget-core").apply { start() }
    private val handler = Handler(thread.looper)
    private val random = SecureRandom()
    private val started = SystemClock.elapsedRealtime()
    private val transport = WsTransport(::post, this)
    private val mic = Microphone { samples -> post { if (micOn) NativeCore.micSamples(handle, samples, samples.size) } }
    private val speaker = Speaker()
    private var handle = 0L
    private var micOn = false // core thread only

    private val tick = object : Runnable {
        override fun run() {
            if (handle == 0L) return
            NativeCore.tick(handle)
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
        handler.post(tick)
    }

    /** Stops the core and its drivers and waits briefly for it; the instance can't be restarted. */
    fun stop() {
        post {
            handler.removeCallbacks(tick)
            mic.stop()
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
        if (handle != 0L) NativeCore.transportOpen(handle)
    }

    override fun onText(text: String) {
        if (handle != 0L) NativeCore.transportText(handle, text.toByteArray())
    }

    override fun onBinary(data: ByteArray) {
        if (handle != 0L) NativeCore.transportBinary(handle, data)
    }

    override fun onClosed(reason: String) {
        if (handle != 0L) NativeCore.transportClosed(handle, reason.toByteArray())
    }

    // -- NativeHost (core thread) ---------------------------------------------------------

    override fun transportConnect(url: ByteArray, subprotocol: ByteArray) =
        transport.connect(url.decodeToString(), subprotocol.decodeToString())

    override fun transportSendText(data: ByteArray) = transport.sendText(data.decodeToString())

    override fun transportSendBinary(data: ByteArray) = transport.sendBinary(data)

    override fun transportClose() = transport.close()

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

    override fun micStart(rate: Int): Boolean {
        micOn = mic.start(rate)
        return micOn
    }

    override fun micStop() {
        micOn = false
        mic.stop()
    }

    override fun speakerBegin(rate: Int) = speaker.begin(rate)

    override fun speakerWrite(samples: ShortArray) = speaker.write(samples)

    override fun speakerEnd() = speaker.end()

    override fun speakerAbort() = speaker.abort()

    override fun speakerBusy() = speaker.busy()

    override fun speakerVolume(percent: Int) = speaker.setVolume(percent)

    override fun storageGet(key: ByteArray): ByteArray? = store.get(key.decodeToString())?.toByteArray()

    override fun storageSet(key: ByteArray, value: ByteArray) = persist { store.set(key.decodeToString(), value.decodeToString()) }

    override fun storageErase(key: ByteArray) = persist { store.erase(key.decodeToString()) }

    override fun nowMs(): Long = SystemClock.elapsedRealtime() - started

    override fun randomBytes(count: Int): ByteArray = ByteArray(count).also(random::nextBytes)

    override fun log(level: Int, message: ByteArray) {
        // The core logs connection state and action failures, never device keys.
        Log.println(LOG_PRIORITY.getOrElse(level) { Log.INFO }, TAG, message.decodeToString())
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
        private val LOG_PRIORITY = intArrayOf(Log.DEBUG, Log.INFO, Log.WARN, Log.ERROR)
    }
}

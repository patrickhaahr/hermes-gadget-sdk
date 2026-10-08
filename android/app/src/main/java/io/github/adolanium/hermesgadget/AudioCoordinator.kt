package io.github.adolanium.hermesgadget

/** Who has the microphone, as the screen shows it. */
enum class AudioState {
    /** The user turned the microphone off: nothing captures until they turn it on. */
    MICROPHONE_OFF,

    /** Listening on the phone for "Hey Hermes". The audio stays on the phone. */
    WAKE_LISTENING,

    /** Hold-to-talk: the device core records and sends the audio to Hermes. */
    GADGET_CAPTURE,

    /** The gadget is speaking, or has just finished; wake listening waits. */
    GADGET_PLAYBACK,

    /** Another owner (a voice call) has the microphone and speaker. */
    HANDED_OFF,

    /** The microphone is on but wake listening can't run; hold-to-talk still works. */
    WAKE_UNAVAILABLE,
}

data class AudioStatus(
    val state: AudioState,
    /** Wake detections since the app started. */
    val detections: Int = 0,
    val lastDetectionScore: Float? = null,
    /** Why wake listening is unavailable, for [AudioState.WAKE_UNAVAILABLE]. */
    val problem: String? = null,
)

/** The user's Microphone off choice, kept across restarts. */
interface MicrophoneSetting {
    var enabled: Boolean
}

/** The speaker as the device core drives it (the core's AudioOut). */
interface Playback {
    fun begin(sampleRate: Int): Boolean
    fun write(samples: ShortArray)
    fun end()
    fun abort()
    fun busy(): Boolean
    fun setVolume(percent: Int)
}

/**
 * Decides who uses the microphone: wake listening, the core's hold-to-talk, or
 * an owner that takes over both microphone and speaker (a voice call), never
 * two at once. The core's microphone and speaker calls come through here.
 *
 * - Microphone off wins over everything and is kept across restarts.
 * - Hold-to-talk takes the microphone from wake listening and gives it back.
 * - Wake listening stops while the gadget speaks and resumes [PLAYBACK_TAIL_MS]
 *   after, with a fresh detector, so its own speech can't wake it.
 * - A detection is only reported: it never presses a control or answers a
 *   prompt, and the wake audio goes to the detector alone.
 *
 * Every method runs on one thread (the core's); [post] queues work onto it.
 */
class AudioCoordinator(
    private val capture: Capture,
    private val playback: Playback,
    private val wake: WakeEngine?,
    private val setting: MicrophoneSetting,
    private val post: (Runnable) -> Unit,
    private val toCore: (ShortArray) -> Unit,
    private val onStatus: (AudioStatus) -> Unit,
) {
    private var micEnabled = setting.enabled
    private var now = 0L
    private var gadgetCapture: Any? = null // token of the running hold-to-talk capture
    private var wakeCapture: Any? = null // token of the running wake session
    private var playing = false
    private var handedOff = false
    private var wakeResumeAt = 0L
    private var captureRetryAt = 0L
    private var captureProblem: String? = null
    private var detections = 0
    private var lastScore: Float? = null
    private var published: AudioStatus? = null

    val status: AudioStatus get() = AudioStatus(state(), detections, lastScore, if (state() == AudioState.WAKE_UNAVAILABLE) problem() else null)

    /** Applies the saved setting; call once the owning thread runs. */
    fun start() = update()

    fun tick(nowMs: Long) {
        now = nowMs
        if (playing && !playback.busy()) {
            playing = false
            wakeResumeAt = now + PLAYBACK_TAIL_MS
        }
        update()
    }

    fun setMicrophoneEnabled(enabled: Boolean) {
        if (enabled == micEnabled) return
        setting.enabled = enabled
        micEnabled = enabled
        if (!enabled && gadgetCapture != null) {
            // The core keeps its recording state; it just gets no more audio.
            capture.close()
            gadgetCapture = null
        }
        captureRetryAt = 0
        update()
    }

    // -- the core's microphone ------------------------------------------------------------

    fun micStart(rate: Int): Boolean {
        if (!micEnabled || handedOff) return false
        stopWake()
        val token = Any()
        gadgetCapture = token
        val ok = capture.open(rate) { samples -> post { if (gadgetCapture === token) toCore(samples) } }
        if (!ok) gadgetCapture = null
        update()
        return ok
    }

    fun micStop() {
        if (gadgetCapture == null) return
        capture.close()
        gadgetCapture = null
        update()
    }

    // -- the core's speaker -----------------------------------------------------------------

    fun speakerBegin(rate: Int): Boolean {
        if (handedOff) return false
        playing = true
        update() // the wake capture stops before the first sample plays
        if (playback.begin(rate)) return true
        playing = false
        update()
        return false
    }

    fun speakerWrite(samples: ShortArray) = playback.write(samples)

    fun speakerEnd() = playback.end()

    fun speakerAbort() = playback.abort()

    fun speakerBusy() = playback.busy()

    fun speakerVolume(percent: Int) = playback.setVolume(percent)

    // -- handing off to a voice call ----------------------------------------------------

    /**
     * Gives the microphone and speaker to another owner: wake listening stops,
     * the gadget's playback is cut and the core can't record or play until
     * [release]. Refused while the microphone is off, during hold-to-talk, or
     * while another owner has them.
     */
    fun handOff(): Boolean {
        if (!micEnabled || handedOff || gadgetCapture != null) return false
        handedOff = true
        stopWake()
        if (playback.busy()) playback.abort()
        playing = false
        update()
        return true
    }

    fun release() {
        if (!handedOff) return
        handedOff = false
        wakeResumeAt = now + PLAYBACK_TAIL_MS
        update()
    }

    fun close() {
        stopWake()
        if (gadgetCapture != null) capture.close()
        gadgetCapture = null
        wake?.close()
    }

    // -- state ------------------------------------------------------------------------------

    private fun state(): AudioState = when {
        !micEnabled -> AudioState.MICROPHONE_OFF
        handedOff -> AudioState.HANDED_OFF
        gadgetCapture != null -> AudioState.GADGET_CAPTURE
        playing || now < wakeResumeAt -> AudioState.GADGET_PLAYBACK
        wakeCapture != null -> AudioState.WAKE_LISTENING
        else -> AudioState.WAKE_UNAVAILABLE
    }

    private fun problem(): String =
        wake?.problem ?: captureProblem ?: if (wake == null) "wake listening is not available" else "starting"

    private fun wakeWanted() = micEnabled && !handedOff && gadgetCapture == null && !playing && now >= wakeResumeAt &&
        wake != null && wake.problem == null && now >= captureRetryAt

    private fun update() {
        if (wakeWanted()) startWake() else stopWake()
        val s = status
        if (s != published) {
            published = s
            onStatus(s)
        }
    }

    private fun startWake() {
        if (wakeCapture != null) return
        val engine = wake ?: return
        val token = Any()
        val sink = engine.arm { score -> post { detected(token, score) } }
        if (capture.open(WakeDetector.SAMPLE_RATE, sink)) {
            wakeCapture = token
            captureProblem = null
        } else {
            engine.disarm()
            captureProblem = "the microphone is unavailable"
            captureRetryAt = now + CAPTURE_RETRY_MS
        }
    }

    private fun stopWake() {
        if (wakeCapture == null) return
        capture.close()
        wake?.disarm()
        wakeCapture = null
    }

    private fun detected(token: Any, score: Float) {
        if (wakeCapture !== token) return // the session ended before the detection arrived
        detections++
        lastScore = score
        update()
    }

    companion object {
        /** How long wake listening waits after the gadget stops speaking (room echo, output latency). */
        const val PLAYBACK_TAIL_MS = 500L
        const val CAPTURE_RETRY_MS = 5000L
    }
}

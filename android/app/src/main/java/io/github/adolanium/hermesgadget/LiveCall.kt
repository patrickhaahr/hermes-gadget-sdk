package io.github.adolanium.hermesgadget

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** One Live call's media: the WebRTC connection, its microphone and the remote voice. */
interface CallMedia {
    /** Starts negotiating; the SDP offer arrives through [CallMediaEvents.offer]. */
    fun createOffer()

    fun setAnswer(sdp: String)

    /** Releases the microphone, the speaker and the connection. */
    fun close()
}

/** What a [CallMedia] reports. Any thread may call these; [LiveCall] moves them onto its own. */
interface CallMediaEvents {
    fun offer(sdp: String)

    /** The voice service started the session (its `session.started` event): the call is usable. */
    fun ready()

    fun failed(reason: String)
}

fun interface CallMediaFactory {
    /** A new call's media, or null when it can't be created (the reason goes to [CallMediaEvents.failed]). */
    fun create(events: CallMediaEvents): CallMedia?
}

/** The ready cue: a short sound that tells the caller to speak. */
interface Cue {
    fun play()
    fun stop()
}

enum class CallState {
    /** No call. [CallStatus.available] says whether Start call can work now. */
    IDLE,

    /** Negotiating with the voice service; the microphone is the call's, nothing is heard yet. */
    STARTING,

    /** The ready cue has played and the conversation runs. */
    ACTIVE,
}

data class CallStatus(
    val state: CallState,
    /** Why Start call can't work right now, or null when it can. */
    val unavailable: String? = null,
    /** The last outcome, for the screen ("Call ended", "Live call failed: ..."). */
    val feedback: String? = null,
    /** Start call to ready cue, for the last call that became ready. */
    val readyMs: Long? = null,
)

/**
 * Starts and ends the phone's subscription Live call (fork issue #4), on the core thread.
 *
 * - Start call takes the microphone and speaker from the [AudioCoordinator]: wake
 *   listening stops and discards what it heard, and the core can't record or play
 *   until the call gives them back. Nothing captured before Start call is sent.
 * - Signaling runs over the paired gadget connection (`call.*`, docs/protocol.md);
 *   the server decides who may call and in which profile. Audio flows directly
 *   between [CallMedia] and the voice service.
 * - The ready cue plays only once the service has started the session. A call that
 *   isn't ready [START_TIMEOUT_MS] after Start call fails and is cleaned up.
 * - End call, Microphone off, a failure, a lost connection or an unpairing end the
 *   call and give the audio back; wake listening resumes after the coordinator's
 *   tail. Nothing reconnects or retries by itself, and answers for an attempt that
 *   already ended are ignored (the server hangs those up).
 */
class LiveCall(
    private val audio: AudioCoordinator,
    private val media: CallMediaFactory,
    private val cue: Cue,
    private val send: (String) -> Boolean,
    private val post: (Runnable) -> Unit,
    private val clock: () -> Long,
    private val onStatus: (CallStatus) -> Unit,
    private val log: (String) -> Unit = {},
) {
    private inner class Attempt(val id: String, val startedAt: Long) {
        var media: CallMedia? = null
        var announced = false // call.start went out, so the server has something to stop
        var state = CallState.STARTING
    }

    private var current: Attempt? = null
    private var counter = 0
    private var linkUp = false
    private var paired = false
    private var hostOffersCalls = false // the server's welcome listed Live calls
    private var feedback: String? = null
    private var readyMs: Long? = null
    private var published: CallStatus? = null

    val status: CallStatus
        get() = CallStatus(current?.state ?: CallState.IDLE, unavailable(), feedback, readyMs)

    /** Start call. Refusals and failures are reported in [status], never retried. */
    fun start() {
        if (current != null) return
        val reason = unavailable() ?: when (audio.status.state) {
            AudioState.MICROPHONE_OFF -> "Turn the microphone on first"
            AudioState.GADGET_CAPTURE -> "Finish the recording first"
            else -> null
        }
        if (reason != null || !audio.handOff()) {
            feedback = reason ?: "The microphone is in use"
            publish()
            return
        }
        val attempt = Attempt("call-${++counter}-${clock()}", clock())
        current = attempt
        feedback = null
        log("call ${attempt.id}: starting")
        publish()
        attempt.media = media.create(events(attempt)) ?: run {
            fail(attempt, "the phone could not set up the call")
            return
        }
        attempt.media?.createOffer()
    }

    /** End call: hangs up a call that is starting or running. */
    fun end(reason: String = "Call ended") {
        val attempt = current ?: return
        hangUp(attempt, reason)
    }

    /** Microphone off ends the call before it stops all capture; see [AudioCoordinator.setMicrophoneEnabled]. */
    fun setMicrophoneEnabled(enabled: Boolean) {
        if (!enabled) end("Microphone off")
        audio.setMicrophoneEnabled(enabled)
    }

    /** Called every core tick; fails a call that isn't ready in time. */
    fun tick() {
        val attempt = current ?: return
        if (attempt.state == CallState.STARTING && clock() - attempt.startedAt >= START_TIMEOUT_MS) {
            fail(attempt, "no answer within ${START_TIMEOUT_MS / 1000} seconds")
        }
    }

    // -- the gadget connection ------------------------------------------------------------

    fun transportOpened() {
        linkUp = true
        paired = false
        hostOffersCalls = false
        publish()
    }

    fun transportClosed() {
        linkUp = false
        paired = false
        hostOffersCalls = false
        current?.let { teardown(it, "Lost the connection to Hermes") }
        publish()
    }

    /**
     * Every text frame from the server passes here first. Call signaling is handled
     * here; everything else goes on to the core (the return value says whether to).
     */
    fun inbound(text: String): Boolean {
        if (!text.contains("\"type\"")) return true
        val msg = try {
            JSONObject(text)
        } catch (e: JSONException) {
            return true
        }
        when (msg.optString("type")) {
            "welcome" -> {
                paired = msg.optBoolean("paired")
                hostOffersCalls = (msg.optJSONArray("calls") ?: JSONArray()).let { calls -> (0 until calls.length()).any { calls.optString(it) == KIND } }
                publish()
            }
            "paired" -> {
                paired = true
                publish()
            }
            "unpaired" -> {
                paired = false
                current?.let { teardown(it, "This phone is no longer paired") }
                publish()
            }
            "call.answer" -> {
                answered(msg.optString("id"), msg.optString("answer"))
                return false
            }
            "call.error" -> {
                val attempt = current?.takeIf { it.id == msg.optString("id") } ?: return false
                attempt.announced = false // the server has nothing left to stop
                fail(attempt, msg.optString("message").ifEmpty { msg.optString("code") })
                return false
            }
            "call.ended" -> {
                val attempt = current?.takeIf { it.id == msg.optString("id") } ?: return false
                attempt.announced = false
                teardown(attempt, when (msg.optString("reason")) {
                    "unpaired" -> "This phone is no longer paired"
                    else -> "Hermes ended the call"
                })
                return false
            }
        }
        return true
    }

    // -- internals ------------------------------------------------------------------------

    private fun unavailable(): String? = when {
        !linkUp -> "Hermes is not connected"
        !paired -> "This phone is not paired with Hermes"
        !hostOffersCalls -> "This Hermes host does not offer Live calls"
        else -> null
    }

    private fun events(attempt: Attempt) = object : CallMediaEvents {
        override fun offer(sdp: String) = post { if (current === attempt) sendOffer(attempt, sdp) }
        override fun ready() = post { if (current === attempt) ready(attempt) }
        override fun failed(reason: String) = post { if (current === attempt) fail(attempt, reason) }
    }

    private fun sendOffer(attempt: Attempt, sdp: String) {
        if (attempt.state != CallState.STARTING || attempt.announced) return
        val start = JSONObject().put("type", "call.start").put("id", attempt.id).put("offer", sdp).put("language", LANGUAGE)
        if (!send(start.toString())) {
            fail(attempt, "Hermes is not connected")
            return
        }
        attempt.announced = true
    }

    private fun answered(id: String, sdp: String) {
        val attempt = current ?: return
        if (attempt.id != id || attempt.state != CallState.STARTING || sdp.isEmpty()) return
        log("call ${attempt.id}: answered after ${clock() - attempt.startedAt} ms")
        attempt.media?.setAnswer(sdp)
    }

    private fun ready(attempt: Attempt) {
        if (attempt.state != CallState.STARTING) return
        attempt.state = CallState.ACTIVE
        readyMs = clock() - attempt.startedAt
        log("call ${attempt.id}: ready after $readyMs ms")
        cue.play()
        publish()
    }

    private fun fail(attempt: Attempt, reason: String) {
        val dropped = attempt.state == CallState.ACTIVE
        hangUp(attempt, (if (dropped) "Call dropped: " else "Live call failed: ") + reason)
    }

    private fun hangUp(attempt: Attempt, reason: String) {
        if (attempt.announced) send(JSONObject().put("type", "call.stop").put("id", attempt.id).toString())
        teardown(attempt, reason)
    }

    private fun teardown(attempt: Attempt, reason: String) {
        if (current !== attempt) return
        current = null
        attempt.media?.close()
        attempt.media = null
        cue.stop()
        audio.release()
        feedback = reason
        log("call ${attempt.id}: ended ($reason)")
        publish()
    }

    private fun publish() {
        val s = status
        if (s != published) {
            published = s
            onStatus(s)
        }
    }

    companion object {
        const val KIND = "live"
        const val LANGUAGE = "en"
        const val START_TIMEOUT_MS = 20_000L
    }
}

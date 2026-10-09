package io.github.adolanium.hermesgadget

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** One Live call's media: the WebRTC connection, its microphone and the remote voice. */
interface CallMedia {
    /** Starts negotiating; the SDP offer arrives through [CallMediaEvents.offer]. */
    fun createOffer()

    fun setAnswer(sdp: String)

    /** Resolve a native voice delegation; the voice reads this once. False means it wasn't sent. */
    fun respond(delegation: String, text: String): Boolean

    /** Releases the microphone, the speaker and the connection. */
    fun close()
}

/** What a [CallMedia] reports. Any thread may call these; [LiveCall] moves them onto its own. */
interface CallMediaEvents {
    fun offer(sdp: String)

    fun failed(reason: String)

    /** A text event from the voice service's data channel. Content is never logged. */
    fun message(text: String)
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
 * Owns the phone's subscription Live call and hands-free lifecycle, on the core thread.
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
        val tasks = mutableMapOf<String, Task>()
        var lastSpeechAt = startedAt
        var lastUserAt = startedAt
        var userText = ""
        var userHasDeltas = false
        var userOverflow = false
    }

    private class Task(var turn: String? = null, var answered: Boolean = false)

    private var current: Attempt? = null
    private val processingTurns = mutableSetOf<String>() // Hermes work survives voice teardown
    private var counter = 0
    private var linkUp = false
    private var paired = false
    private var hostOffersCalls = false // the server's welcome listed Live calls
    private var hostOffersTasks = false
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

    /** Startup expiry, spoken hang-up and idle expiry run on the core thread. */
    fun tick() {
        val attempt = current ?: return
        val now = clock()
        if (attempt.state == CallState.STARTING && now - attempt.startedAt >= START_TIMEOUT_MS) {
            fail(attempt, "no answer within ${START_TIMEOUT_MS / 1000} seconds")
        } else if (attempt.state == CallState.ACTIVE) {
            if (attempt.userText.isNotEmpty() && now - attempt.lastUserAt >= UTTERANCE_GAP_MS) {
                val goodbye = !attempt.userOverflow && GOODBYE.matches(attempt.userText)
                clearUser(attempt)
                if (goodbye) {
                    log("call ${attempt.id}: spoken hang-up")
                    hangUp(attempt, "Call ended")
                    return
                }
            }
            if (processingTurns.isEmpty() && attempt.tasks.values.none { !it.answered } && now - attempt.lastSpeechAt >= IDLE_TIMEOUT_MS) {
                hangUp(attempt, "Call ended after 60 seconds without speech")
            }
        }
    }

    // -- the gadget connection ------------------------------------------------------------

    fun transportOpened() {
        linkUp = true
        paired = false
        hostOffersCalls = false
        hostOffersTasks = false
        publish()
    }

    fun transportClosed() {
        linkUp = false
        paired = false
        hostOffersCalls = false
        hostOffersTasks = false
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
                hostOffersTasks = msg.optBoolean("call_tasks")
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
            "turn.start" -> msg.optString("turn").takeIf { it.isNotEmpty() }?.let(processingTurns::add)
            "turn.end" -> {
                if (processingTurns.remove(msg.optString("turn"))) current?.lastSpeechAt = clock()
            }
            "call.task.status" -> {
                taskStatus(msg)
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
        override fun failed(reason: String) = post { if (current === attempt) fail(attempt, reason) }
        override fun message(text: String) = post { if (current === attempt) message(attempt, text) }
    }

    private fun message(attempt: Attempt, text: String) {
        val event = try { JSONObject(text) } catch (e: JSONException) { return }
        when (event.optString("type")) {
            "session.started" -> ready(attempt)
            "input_transcript.added", "output_transcript.added" -> {
                transcript(attempt, event.optString("type") == "input_transcript.added", event.optJSONObject("item")?.optString("text") ?: "", delta = true)
            }
            "turn.done" -> event.optJSONObject("turn")?.let { turn ->
                val role = turn.optString("role")
                val text = turn.optString("transcript")
                log("$role turn done (${text.length} characters)")
                if (role == "user" || role == "assistant") transcript(attempt, role == "user", text, delta = false)
            }
            "error" -> log("voice service reported an error")
            "delegation.created" -> event.optJSONObject("item")?.let { item ->
                val content = item.optJSONArray("content") ?: JSONArray()
                val request = (0 until content.length()).joinToString("") { content.optJSONObject(it)?.optString("text") ?: "" }.trim()
                delegate(attempt, item.optString("id"), request)
            }
        }
    }

    private fun clearUser(attempt: Attempt) {
        attempt.userText = ""
        attempt.userHasDeltas = false
        attempt.userOverflow = false
    }

    private fun transcript(attempt: Attempt, user: Boolean, text: String, delta: Boolean) {
        if (attempt.state != CallState.ACTIVE || text.isEmpty()) return
        val now = clock()
        if (text.isNotBlank()) attempt.lastSpeechAt = now
        if (!user) return // model output can keep a call alive, but never hang it up
        if (now - attempt.lastUserAt >= UTTERANCE_GAP_MS) clearUser(attempt)
        attempt.lastUserAt = now
        // v3 rotates turns and turn.done can be partial or absent. Deltas own the
        // utterance when present; bookkeeping must not replace or duplicate them.
        if (delta) {
            if (!attempt.userHasDeltas) clearUser(attempt)
            attempt.userHasDeltas = true
            attempt.userOverflow = attempt.userOverflow || attempt.userText.length + text.length > MAX_USER_TEXT
            attempt.userText = (attempt.userText + text).take(MAX_USER_TEXT)
        } else if (!attempt.userHasDeltas && text.length > attempt.userText.length) {
            attempt.userOverflow = text.length > MAX_USER_TEXT
            attempt.userText = text.take(MAX_USER_TEXT)
        }
    }

    private fun delegate(attempt: Attempt, id: String, text: String) {
        if (attempt.state != CallState.ACTIVE || id.isEmpty() || id.length > 64 || id in attempt.tasks) return
        if (attempt.tasks.size >= 256) {
            hangUp(attempt, "This call has reached its task request limit")
            return
        }
        attempt.lastSpeechAt = clock()
        val task = Task()
        attempt.tasks[id] = task // duplicate service events must never submit again
        if (!hostOffersTasks) {
            task.answered = true
            attempt.media?.respond(id, "This Hermes host does not support tasks from phone calls. Ask the user to update the gadget plugin. You can still converse normally.")
            return
        }
        if (text.isBlank() || text.length > 4000) {
            task.answered = true
            attempt.media?.respond(id, "The task request was empty or too long. Ask the user to rephrase it. Nothing was submitted.")
            return
        }
        val request = JSONObject().put("type", "call.task").put("id", attempt.id).put("delegation", id).put("text", text)
        log("call ${attempt.id}: task $id requested (${text.length} characters)")
        if (!send(request.toString())) {
            task.answered = true
            attempt.media?.respond(id, "The connection to Hermes failed. Task acceptance is unknown. Do not retry automatically.")
            feedback = "Could not confirm task acceptance; nothing will be retried"
            publish()
        }
    }

    private fun taskStatus(msg: JSONObject) {
        val attempt = current?.takeIf { it.id == msg.optString("id") && it.state == CallState.ACTIVE } ?: return
        val id = msg.optString("delegation")
        val task = attempt.tasks[id]?.takeUnless { it.answered } ?: return
        val state = msg.optString("state")
        val turn = msg.optString("turn")
        if (state == "accepted") {
            if (turn.isEmpty() || (task.turn != null && task.turn != turn)) return
            task.turn = turn
            processingTurns.add(turn)
            feedback = "Hermes is working; you can keep talking"
        } else if (state in listOf("completed", "busy", "failed", "not_connected")) {
            if (task.turn != null && task.turn != turn) return
            task.answered = true // a duplicate receipt must never speak the result twice
            if (task.turn != null) processingTurns.remove(turn)
            attempt.lastSpeechAt = clock() // give the voice a full idle window to read the result
            val text = msg.optString("text").ifBlank { "The Hermes task could not be completed." }
            val response = if (state == "completed") "Hermes task result. Answer the user briefly with this:\n$text" else text
            val spoken = attempt.media?.respond(id, response) == true
            feedback = if (!spoken) "The task response could not be sent to the voice" else when (state) {
                "completed" -> "Hermes task completed"
                "busy" -> "Hermes is busy; wait, then ask again"
                "not_connected" -> "The task's call is no longer connected"
                else -> "Hermes task failed"
            }
        } else return
        log("call ${attempt.id}: task $id $state${if (turn.isNotEmpty()) " (turn $turn)" else ""}")
        publish()
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
        attempt.lastSpeechAt = clock()
        attempt.lastUserAt = clock()
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
        audio.release(clock())
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
        const val IDLE_TIMEOUT_MS = 60_000L
        // A whole user utterance, including any continuation, must settle first.
        private const val UTTERANCE_GAP_MS = 3500L
        private const val MAX_USER_TEXT = 256
        private val GOODBYE = Regex("""(?i)\s*goodbye[\s,]+hermes[.!?,…]*\s*""")
    }
}

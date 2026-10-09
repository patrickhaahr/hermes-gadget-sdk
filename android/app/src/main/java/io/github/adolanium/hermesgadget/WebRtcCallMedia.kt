package io.github.adolanium.hermesgadget

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.charset.StandardCharsets
import java.util.Timer
import kotlin.concurrent.fixedRateTimer
import kotlin.math.abs
import kotlin.math.log10

/** Creates [WebRtcCallMedia] for each call. */
class WebRtcCalls(context: Context) : CallMediaFactory {
    private val app = context.applicationContext

    override fun create(events: CallMediaEvents): CallMedia? {
        initialize(app)
        return try {
            WebRtcCallMedia(app, events)
        } catch (e: RuntimeException) {
            Log.w(TAG, "the call could not be set up", e)
            events.failed("the phone could not set up the call: ${e.message}")
            null
        }
    }

    private companion object {
        @Volatile var initialized = false

        @Synchronized
        fun initialize(context: Context) {
            if (initialized) return
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
            initialized = true
        }
    }
}

/**
 * One Live call over native WebRTC, as the Live Voice desktop client makes it: the
 * microphone track plus an `oai-events` data channel in one offer, and the remote
 * voice played as it arrives. The audio path is the phone's communication path:
 * the voice-communication microphone source and playback on the loudspeaker in
 * communication mode. Where the phone has its own echo canceller and noise
 * suppressor, they are used and WebRTC switches off its software ones; WebRTC's
 * gain control and high-pass filter stay on. That the echo cancellation is good
 * enough on a given phone is a physical measurement (docs/hardware-validation.md).
 */
class WebRtcCallMedia(context: Context, private val events: CallMediaEvents) : CallMedia {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val savedMode = audioManager.mode
    private val platformAec = JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported()
    private val platformNs = JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported()
    @Volatile private var micPeak = 0 // loudest captured sample since the last stats line
    private val adm = JavaAudioDeviceModule.builder(context)
        .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        .setUseHardwareAcousticEchoCanceler(platformAec)
        .setUseHardwareNoiseSuppressor(platformNs)
        .setSamplesReadyCallback { samples -> notePeak(samples.data) }
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build())
        .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
            override fun onWebRtcAudioRecordInitError(message: String) = events.failed("microphone: $message")
            override fun onWebRtcAudioRecordStartError(code: JavaAudioDeviceModule.AudioRecordStartErrorCode, message: String) =
                events.failed("microphone: $message")
            override fun onWebRtcAudioRecordError(message: String) = events.failed("microphone: $message")
        })
        .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
            override fun onWebRtcAudioTrackInitError(message: String) = events.failed("speaker: $message")
            override fun onWebRtcAudioTrackStartError(code: JavaAudioDeviceModule.AudioTrackStartErrorCode, message: String) =
                events.failed("speaker: $message")
            override fun onWebRtcAudioTrackError(message: String) = events.failed("speaker: $message")
        })
        .createAudioDeviceModule()
    private val factory = PeerConnectionFactory.builder().setAudioDeviceModule(adm).createPeerConnectionFactory()
    private val source = factory.createAudioSource(MediaConstraints().apply {
        for (key in listOf("googEchoCancellation", "googNoiseSuppression", "googAutoGainControl", "googHighpassFilter")) {
            mandatory.add(MediaConstraints.KeyValuePair(key, "true"))
        }
    })
    private val track = factory.createAudioTrack("hermes-mic", source)
    private val peer: PeerConnection
    private val channel: DataChannel
    @Volatile private var closed = false
    @Volatile private var ready = false
    private var stats: Timer? = null

    init {
        Log.i(TAG, "audio: voice-communication microphone, ${if (platformAec) "the phone's" else "WebRTC's"} echo canceller, " +
            "${if (platformNs) "the phone's" else "WebRTC's"} noise suppressor, loudspeaker")
        routeToLoudspeaker()
        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        peer = factory.createPeerConnection(config, Observer()) ?: run {
            release()
            throw IllegalStateException("WebRTC refused the connection")
        }
        peer.addTrack(track, listOf("hermes"))
        channel = peer.createDataChannel("oai-events", DataChannel.Init())
        channel.registerObserver(Channel())
    }

    override fun createOffer() {
        peer.createOffer(object : Sdp("offer") {
            override fun onCreateSuccess(desc: SessionDescription) {
                // Sent without waiting for ICE gathering, as the desktop client does: the
                // service is ICE-lite and finds this side through its connectivity checks.
                peer.setLocalDescription(object : Sdp("local description") {
                    override fun onSetSuccess() {
                        if (!closed) events.offer(desc.description)
                    }
                }, desc)
            }
        }, MediaConstraints())
    }

    override fun setAnswer(sdp: String) {
        if (!closed) peer.setRemoteDescription(Sdp("answer"), SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        stats?.cancel()
        channel.unregisterObserver()
        channel.close()
        channel.dispose()
        peer.dispose() // closes the connection and its senders
        release()
    }

    private fun release() {
        source.dispose()
        factory.dispose()
        adm.release() // the microphone and speaker are free once this returns
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.clearCommunicationDevice()
        audioManager.mode = savedMode
    }

    private fun routeToLoudspeaker() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?.let(audioManager::setCommunicationDevice)
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true
        }
    }

    /**
     * Bytes each way and the loudest captured sample: evidence that capture and
     * playback ran, and how much of the loudspeaker the echo canceller left. (The
     * stats' own microphone level stays 0 with the phone's audio processing.)
     */
    private fun logStats(label: String) = synchronized(this) {
        if (ready && !closed) {
            val peak = micPeak
            micPeak = 0
            peer.getStats { report ->
                var sent = 0L
                var received = 0L
                for (s in report.statsMap.values) {
                    when (s.type) {
                        "outbound-rtp" -> sent += (s.members["bytesSent"] as? Number)?.toLong() ?: 0
                        "inbound-rtp" -> received += (s.members["bytesReceived"] as? Number)?.toLong() ?: 0
                    }
                }
                val level = if (peak == 0) "silent" else "%.0f dBFS".format(20 * log10(peak / 32768.0))
                Log.i(TAG, "$label: sent ${sent / 1024} KB, received ${received / 1024} KB, microphone peak $level")
            }
        }
    }

    /** Called on the recording thread with each 10 ms of captured 16-bit PCM. */
    private fun notePeak(pcm: ByteArray) {
        var peak = micPeak
        for (i in 0 until pcm.size - 1 step 2) {
            val sample = abs((pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8))
            if (sample > peak) peak = sample
        }
        micPeak = peak
    }

    private inner class Observer : PeerConnection.Observer {
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            Log.i(TAG, "connection: $state")
            if (state == PeerConnection.PeerConnectionState.FAILED && !closed) events.failed("the connection to the voice service failed")
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
        override fun onIceCandidate(candidate: IceCandidate) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onDataChannel(channel: DataChannel) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
            Log.i(TAG, "the voice service's audio arrived (${receiver.track()?.kind()})")
        }
    }

    private inner class Channel : DataChannel.Observer {
        override fun onBufferedAmountChange(previous: Long) {}

        override fun onStateChange() {
            val state = channel.state()
            Log.i(TAG, "events channel: $state")
            if (state == DataChannel.State.CLOSED && ready && !closed) events.failed("the voice service closed the call")
        }

        override fun onMessage(buffer: DataChannel.Buffer) {
            if (buffer.binary) return
            val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
            val event = try {
                JSONObject(String(bytes, StandardCharsets.UTF_8))
            } catch (e: JSONException) {
                return
            }
            when (event.optString("type")) {
                "session.started" -> if (!ready) {
                    ready = true
                    stats = fixedRateTimer("call-stats", daemon = true, initialDelay = STATS_MS, period = STATS_MS) { logStats("call audio") }
                    events.ready()
                }
                // Only the turn's role and length: what was said stays out of the log.
                "turn.done" -> event.optJSONObject("turn")?.let { turn ->
                    Log.i(TAG, "${turn.optString("role")} turn done (${turn.optString("transcript").length} characters)")
                }
                "error" -> Log.w(TAG, "voice service error: ${event.optJSONObject("error")?.optString("message") ?: event}")
                // Hermes tasks from a phone call arrive with fork issue #6. Until then the voice
                // says so instead of waiting for a result that never comes.
                "delegation.created" -> event.optJSONObject("item")?.optString("id")?.takeIf { it.isNotEmpty() }?.let { item ->
                    Log.i(TAG, "declined a task request: tasks are not available in phone calls yet")
                    send(JSONObject().put("type", "delegation.context.append").put("delegation_item_id", item)
                        .put("content", org.json.JSONArray().put(JSONObject().put("type", "input_text").put("text", NO_TASKS))))
                }
            }
        }
    }

    private open inner class Sdp(private val what: String) : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {
            if (!closed) events.failed("WebRTC could not create the $what: $error")
        }
        override fun onSetFailure(error: String?) {
            if (!closed) events.failed("WebRTC rejected the $what: $error")
        }
    }

    private fun send(event: JSONObject) {
        if (closed) return
        val bytes = event.toString().toByteArray(StandardCharsets.UTF_8)
        channel.send(DataChannel.Buffer(java.nio.ByteBuffer.wrap(bytes), false))
    }

    private companion object {
        const val STATS_MS = 10_000L
        const val NO_TASKS = "Hermes can't run tasks from a phone call yet. Tell the user briefly that you can talk, " +
            "but can't do that from this call; they can ask Hermes by holding the screen or with \"Hey Hermes\" instead."
    }
}

private const val TAG = "HermesCall"

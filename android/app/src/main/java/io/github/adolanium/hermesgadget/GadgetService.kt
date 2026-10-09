package io.github.adolanium.hermesgadget

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import java.io.IOException

/** Process-wide state shared by the service and the activities. */
object GadgetRuntime {
    @Volatile var core: GadgetCore? = null
    @Volatile var frameListener: (() -> Unit)? = null
    @Volatile var audioListener: ((AudioStatus) -> Unit)? = null
    @Volatile var callListener: ((CallStatus) -> Unit)? = null
    @Volatile var stopReason: String? = null
    private var store: DeviceStore? = null

    @Synchronized
    fun store(context: Context): DeviceStore =
        store ?: DeviceStore(context.applicationContext.noBackupFilesDir).also { store = it }

    /** Microphone off, saved before it takes effect so a restart can't undo it. */
    fun microphone(context: Context): MicrophoneSetting = object : MicrophoneSetting {
        private val prefs = context.applicationContext.getSharedPreferences("audio", Context.MODE_PRIVATE)
        override var enabled: Boolean
            get() = prefs.getBoolean(MICROPHONE_ENABLED, true)
            set(value) {
                prefs.edit().putBoolean(MICROPHONE_ENABLED, value).commit()
            }
    }

    fun voiceMode(context: Context): VoiceMode {
        val saved = context.applicationContext.getSharedPreferences("audio", Context.MODE_PRIVATE).getString("voice_mode", "hermes")
        return if (saved == "live") VoiceMode.LIVE else VoiceMode.HERMES
    }

    /** Unsupported Live is refused, with no fallback or change to the saved choice. */
    fun setVoiceMode(context: Context, mode: VoiceMode): Boolean {
        if (mode == VoiceMode.LIVE) return false
        return context.applicationContext.getSharedPreferences("audio", Context.MODE_PRIVATE)
            .edit().putString("voice_mode", mode.value).commit()
    }

    /** The microphone's state; without a running core, only the saved choice is known. */
    fun audioStatus(context: Context): AudioStatus = core?.audioStatus
        ?: AudioStatus(if (microphone(context).enabled) AudioState.WAKE_UNAVAILABLE else AudioState.MICROPHONE_OFF,
            problem = "the gadget is not running")

    /** The Live call's state; without a running core there is no call. */
    fun callStatus(): CallStatus = core?.callStatus ?: CallStatus(CallState.IDLE, unavailable = "the gadget is not running")

    /** Start call, or End call when one is starting or running. */
    fun toggleCall() {
        val core = core ?: return
        if (core.callStatus.state == CallState.IDLE) core.startCall() else core.endCall()
    }

    /** Turns the microphone on or off, saving the choice first. */
    fun setMicrophoneEnabled(context: Context, enabled: Boolean) {
        microphone(context).enabled = enabled
        core?.setMicrophoneEnabled(enabled)
    }

    /** The logical screen: 360 px wide, as tall as the phone's aspect allows (the core's UI scales from it). */
    fun frameSize(context: Context): Pair<Int, Int> {
        val metrics = context.resources.displayMetrics
        val (short, long) = minOf(metrics.widthPixels, metrics.heightPixels) to maxOf(metrics.widthPixels, metrics.heightPixels)
        val height = (FRAME_WIDTH * long / short).coerceIn(FRAME_WIDTH, 800) and 1.inv()
        return FRAME_WIDTH to height
    }

    private const val FRAME_WIDTH = 360
    private const val MICROPHONE_ENABLED = "microphone_enabled"
}

/**
 * Keeps the device core running with the screen off: a foreground service that
 * owns the microphone, holds a partial wake lock and a Wi-Fi lock, and reports
 * the network to the core.
 */
class GadgetService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION_ID, notification(), foregroundTypes())
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HermesGadget:core").apply { acquire() }
        @Suppress("DEPRECATION") // WIFI_MODE_FULL_LOW_LATENCY needs the screen on; this keeps Wi-Fi up when it's off.
        wifiLock = getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "HermesGadget:ws").apply { acquire() }
        startCore()
        watchNetwork()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CONFIGURE) {
            val config = GadgetConfig(
                intent.getStringExtra(EXTRA_SERVER).orEmpty(),
                intent.getStringExtra(EXTRA_NAME)?.takeIf { it.isNotBlank() } ?: GadgetConfig.DEFAULT_NAME,
                intent.getStringExtra(EXTRA_TOKEN).orEmpty(),
            )
            configure(config)
        } else if (GadgetRuntime.core == null) {
            startCore()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        networkCallback?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        GadgetRuntime.core?.stop()
        GadgetRuntime.core = null
        wifiLock?.release()
        wakeLock?.release()
        super.onDestroy()
    }

    private fun configure(config: GadgetConfig) {
        config.problem()?.let {
            Log.w(TAG, "configuration rejected: $it")
            return
        }
        // Stop the core first: it's the only other writer of the store.
        GadgetRuntime.core?.stop()
        GadgetRuntime.core = null
        try {
            config.writeTo(GadgetRuntime.store(this))
        } catch (e: IOException) {
            GadgetRuntime.stopReason = "Settings could not be saved: ${e.message}"
            Log.e(TAG, "settings could not be saved", e)
            return
        }
        startCore()
        lastNetwork?.let { GadgetRuntime.core?.network(true, it) }
    }

    private fun startCore() {
        if (GadgetRuntime.core != null) return
        val (width, height) = GadgetRuntime.frameSize(this)
        val firmware = packageManager.getPackageInfo(packageName, 0).versionName ?: "0.0.0"
        GadgetRuntime.stopReason = null
        val wake = WakeListener(log = { Log.i(WAKE_TAG, it) }) { WakeDetector(LiteRtModel.fromAssets(assets)) }
        val core = GadgetCore(GadgetRuntime.store(this), firmware, Frame(width, height), GadgetRuntime.microphone(this), wake,
            onFrame = { GadgetRuntime.frameListener?.invoke() },
            onAudio = { status -> GadgetRuntime.audioListener?.invoke(status) },
            voiceMode = { GadgetRuntime.voiceMode(this) },
            callMedia = WebRtcCalls(this),
            cue = ReadyCue(),
            onCall = { status -> GadgetRuntime.callListener?.invoke(status) },
            onStopped = { reason ->
                GadgetRuntime.stopReason = reason
                GadgetRuntime.frameListener?.invoke()
            })
        GadgetRuntime.core = core
        core.start()
    }

    @Volatile private var lastNetwork: String? = null

    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val detail = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
                    else -> "network"
                }
                if (detail != lastNetwork) {
                    Log.i(TAG, "network: $detail")
                    lastNetwork = detail
                    GadgetRuntime.core?.network(true, detail)
                }
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "network lost")
                lastNetwork = null
                GadgetRuntime.core?.network(false, "")
            }
        }
        cm.registerDefaultNetworkCallback(callback)
        networkCallback = callback
    }

    private fun foregroundTypes(): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        // A microphone service needs the permission first (Android 14 enforces it).
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return types
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.service_running))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_CONFIGURE = "io.github.adolanium.hermesgadget.CONFIGURE"
        const val EXTRA_SERVER = "server"
        const val EXTRA_NAME = "name"
        const val EXTRA_TOKEN = "token"
        private const val CHANNEL = "gadget"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "HermesGadget"
        private const val WAKE_TAG = "HermesWake"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, GadgetService::class.java))
        }
    }
}

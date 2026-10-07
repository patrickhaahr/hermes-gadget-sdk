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
    @Volatile var stopReason: String? = null
    private var store: DeviceStore? = null

    @Synchronized
    fun store(context: Context): DeviceStore =
        store ?: DeviceStore(context.applicationContext.noBackupFilesDir).also { store = it }

    /** The logical screen: 360 px wide, as tall as the phone's aspect allows (the core's UI scales from it). */
    fun frameSize(context: Context): Pair<Int, Int> {
        val metrics = context.resources.displayMetrics
        val (short, long) = minOf(metrics.widthPixels, metrics.heightPixels) to maxOf(metrics.widthPixels, metrics.heightPixels)
        val height = (FRAME_WIDTH * long / short).coerceIn(FRAME_WIDTH, 800) and 1.inv()
        return FRAME_WIDTH to height
    }

    private const val FRAME_WIDTH = 360
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
        val core = GadgetCore(GadgetRuntime.store(this), firmware, Frame(width, height),
            onFrame = { GadgetRuntime.frameListener?.invoke() },
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

        fun start(context: Context) {
            context.startForegroundService(Intent(context, GadgetService::class.java))
        }
    }
}

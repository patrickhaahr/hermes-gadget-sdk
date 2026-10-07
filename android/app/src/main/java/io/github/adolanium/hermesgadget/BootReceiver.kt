package io.github.adolanium.hermesgadget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts the gadget after a reboot or an app update. Android lets a device owner
 * start a microphone service from here; otherwise the home screen starts it
 * once the phone is unlocked.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (GadgetConfig.readFrom(GadgetRuntime.store(context)) == null) return
        try {
            GadgetService.start(context)
        } catch (e: RuntimeException) { // ForegroundServiceStartNotAllowedException, SecurityException
            Log.w("HermesGadget", "service not started from ${intent.action}; the home screen will start it", e)
        }
    }
}

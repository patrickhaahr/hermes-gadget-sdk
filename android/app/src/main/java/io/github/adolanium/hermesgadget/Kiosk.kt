package io.github.adolanium.hermesgadget

import android.Manifest
import android.app.Activity
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

/** The device-admin component that `dpm set-device-owner` names. */
class AdminReceiver : DeviceAdminReceiver()

/**
 * Dedicated-device mode, active only when this app is the device owner
 * (`adb shell dpm set-device-owner .../.AdminReceiver`): the app is the home
 * screen, runs pinned in lock task mode, holds its runtime permissions and may
 * start its microphone service after a reboot.
 */
object Kiosk {
    private val PERMISSIONS = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun isOwner(context: Context): Boolean =
        context.getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(context.packageName)

    /** Paused by hand from the settings screen, for maintenance; survives restarts. */
    fun isPaused(context: Context): Boolean = prefs(context).getBoolean(PAUSED, false)

    fun enter(activity: Activity) {
        if (!isOwner(activity) || isPaused(activity)) return
        val dpm = activity.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(activity, AdminReceiver::class.java)
        for (permission in PERMISSIONS) {
            dpm.setPermissionGrantState(admin, activity.packageName, permission,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
        }
        dpm.setLockTaskPackages(admin, arrayOf(activity.packageName))
        // Keep the power menu so the phone can still be restarted by hand.
        dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS)
        val home = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        dpm.addPersistentPreferredActivity(admin, home, ComponentName(activity, MainActivity::class.java))
        dpm.setKeyguardDisabled(admin, true)
        activity.startLockTask()
    }

    /** Undoes [enter]; with [release] also gives up device ownership for good. */
    fun leave(activity: Activity, release: Boolean) {
        prefs(activity).edit().putBoolean(PAUSED, !release).apply()
        activity.stopLockTask()
        if (!isOwner(activity)) return
        val dpm = activity.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(activity, AdminReceiver::class.java)
        dpm.clearPackagePersistentPreferredActivities(admin, activity.packageName)
        dpm.setKeyguardDisabled(admin, false)
        dpm.setLockTaskPackages(admin, emptyArray())
        if (release) dpm.clearDeviceOwnerApp(activity.packageName)
    }

    fun resume(activity: Activity) {
        prefs(activity).edit().putBoolean(PAUSED, false).apply()
        enter(activity)
    }

    private fun prefs(context: Context) = context.getSharedPreferences("kiosk", Context.MODE_PRIVATE)

    private const val PAUSED = "paused"
}

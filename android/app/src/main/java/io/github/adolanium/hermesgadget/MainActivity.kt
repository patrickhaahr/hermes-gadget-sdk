package io.github.adolanium.hermesgadget

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * The gadget's face, full screen, with the microphone's state over it. It is
 * also the home screen, so it comes back after a reboot. Hold volume-up for
 * three seconds to open the settings.
 */
class MainActivity : Activity() {
    private lateinit var face: FaceView
    private lateinit var listening: ListeningChip
    private var backlight = -1
    private val openSettings = Runnable { startActivity(Intent(this, SetupActivity::class.java)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        face = FaceView(this)
        listening = ListeningChip(this) { enable -> GadgetRuntime.setMicrophoneEnabled(this, enable) }
        setContentView(FrameLayout(this).apply {
            addView(face)
            addView(listening.chip)
            addView(listening.banner)
        })
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // Dialogs such as Android's "unblock microphone" bring the system bars back.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onResume() {
        super.onResume()
        Kiosk.enter(this)
        GadgetRuntime.frameListener = { face.post(::refresh) }
        GadgetRuntime.audioListener = { status -> face.post { listening.show(status) } }
        // Detections while the face was hidden are old news.
        listening.show(GadgetRuntime.audioStatus(this), announce = false)
        val missing = listOfNotNull(
            Manifest.permission.RECORD_AUDIO,
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null,
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
            return
        }
        launch()
    }

    override fun onPause() {
        GadgetRuntime.frameListener = null
        GadgetRuntime.audioListener = null
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        // Start either way: without the microphone the gadget still shows replies.
        if (requestCode == REQUEST_PERMISSIONS) launch()
    }

    private fun launch() {
        if (GadgetConfig.readFrom(GadgetRuntime.store(this)) == null) {
            startActivity(Intent(this, SetupActivity::class.java))
            return
        }
        GadgetService.start(this)
        refresh()
    }

    private fun refresh() {
        val level = GadgetRuntime.core?.frame?.backlight ?: 100
        if (level != backlight) {
            backlight = level
            // At zero the core has put the display to sleep: let Android turn the screen off.
            if (level == 0) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.attributes = window.attributes.apply {
                screenBrightness = if (level == 0) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF else level / 100f
            }
        }
        face.invalidate()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            event.startTracking()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            face.postDelayed(openSettings, SETTINGS_HOLD_MS)
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            face.removeCallbacks(openSettings)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private companion object {
        const val REQUEST_PERMISSIONS = 1
        // Added to the system's long-press delay (about 0.5 s).
        const val SETTINGS_HOLD_MS = 2500L
    }
}

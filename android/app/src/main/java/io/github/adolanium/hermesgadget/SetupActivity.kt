package io.github.adolanium.hermesgadget

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Connection settings and maintenance. Also scriptable over adb:
 *
 *     adb shell am start -n io.github.adolanium.hermesgadget/.AdbSetup \
 *         --es server ws://zaza:8765/gadget --es name "'Robot head'" [--es token SECRET]
 *
 * and for the core's console commands, answered in the log (`adb logcat -s HermesGadget`):
 *
 *     adb shell am start -n io.github.adolanium.hermesgadget/.AdbSetup --es console "'set screen_timeout 60'"
 */
class SetupActivity : Activity() {
    private lateinit var server: EditText
    private lateinit var name: EditText
    private lateinit var token: EditText
    private lateinit var message: TextView
    private lateinit var status: TextView
    private val poll = object : Runnable {
        override fun run() {
            val core = GadgetRuntime.core
            if (core == null) status.text = GadgetRuntime.stopReason ?: getString(R.string.status_stopped)
            else core.status { json -> status.post { status.text = json } }
            status.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra(EXTRA_CONSOLE)?.let { line ->
            val core = GadgetRuntime.core
            if (core == null) Log.w(TAG, "console: the gadget is not running")
            else core.console(line) { reply -> Log.i(TAG, "console: $line -> $reply") }
            finish()
            return
        }
        intent.getStringExtra(GadgetService.EXTRA_SERVER)?.let { url ->
            val config = GadgetConfig(url, intent.getStringExtra(GadgetService.EXTRA_NAME) ?: GadgetConfig.DEFAULT_NAME,
                intent.getStringExtra(GadgetService.EXTRA_TOKEN).orEmpty())
            if (apply(config)) {
                finish()
                return
            }
        }
        val saved = GadgetConfig.readFrom(GadgetRuntime.store(this))
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun label(text: Int) = column.addView(TextView(this).apply { setText(text) })
        fun field(value: String?, hint: String, type: Int) = EditText(this).apply {
            setText(value.orEmpty())
            this.hint = hint
            inputType = type
            isSingleLine = true
            column.addView(this)
        }
        fun button(text: Int, action: () -> Unit) = column.addView(Button(this).apply {
            setText(text)
            setOnClickListener { action() }
        })

        label(R.string.label_server)
        server = field(saved?.server, "ws://zaza:8765/gadget", InputType.TYPE_TEXT_VARIATION_URI)
        label(R.string.label_name)
        name = field(saved?.name ?: GadgetConfig.DEFAULT_NAME, GadgetConfig.DEFAULT_NAME, InputType.TYPE_CLASS_TEXT)
        label(R.string.label_token)
        token = field(saved?.token, "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        message = TextView(this).also(column::addView)
        button(R.string.action_save) {
            if (apply(GadgetConfig(server.text.toString().trim(), name.text.toString().trim(), token.text.toString()))) finish()
        }
        button(R.string.action_battery) {
            val pm = getSystemService(PowerManager::class.java)
            if (pm.isIgnoringBatteryOptimizations(packageName)) message.setText(R.string.battery_ok)
            else startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        if (Kiosk.isOwner(this)) {
            if (Kiosk.isPaused(this)) button(R.string.action_kiosk_resume) {
                Kiosk.resume(this)
                finish()
            } else button(R.string.action_kiosk_pause) {
                Kiosk.leave(this, release = false)
                message.setText(R.string.kiosk_paused)
            }
            button(R.string.action_kiosk_release) {
                AlertDialog.Builder(this)
                    .setMessage(R.string.kiosk_release_confirm)
                    .setPositiveButton(R.string.action_kiosk_release) { _, _ ->
                        Kiosk.leave(this, release = true)
                        recreate()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
        status = TextView(this).apply {
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }
        column.addView(status)
        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onResume() {
        super.onResume()
        status.post(poll)
    }

    override fun onPause() {
        status.removeCallbacks(poll)
        super.onPause()
    }

    private fun apply(config: GadgetConfig): Boolean {
        val problem = config.problem()
        if (problem != null) {
            Log.w(TAG, "settings not saved: $problem")
            if (::message.isInitialized) message.text = problem
            return false
        }
        startForegroundService(Intent(this, GadgetService::class.java)
            .setAction(GadgetService.ACTION_CONFIGURE)
            .putExtra(GadgetService.EXTRA_SERVER, config.server)
            .putExtra(GadgetService.EXTRA_NAME, config.name)
            .putExtra(GadgetService.EXTRA_TOKEN, config.token))
        return true
    }

    private companion object {
        const val EXTRA_CONSOLE = "console"
        const val TAG = "HermesGadget"
    }
}

package io.github.adolanium.hermesgadget

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Start call and End call over the face, bottom centre, with the last outcome
 * ("Call ended", why a call failed) above it for a few seconds. Hidden while
 * Hermes can't take a call: not connected, not paired, or no Live calls on the host.
 */
class CallButton(context: Context, private val onPress: () -> Unit) {
    private val density = context.resources.displayMetrics.density
    private var shownFeedback: String? = null

    val button = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setTextColor(Color.WHITE)
        setPadding(dp(24), dp(12), dp(24), dp(12))
        minHeight = dp(48)
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        visibility = View.GONE
        setOnClickListener { onPress() }
        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(32) }
    }

    val note = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(Color.WHITE)
        setPadding(dp(14), dp(6), dp(14), dp(6))
        background = pill(NOTE)
        visibility = View.GONE
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(96) }
    }

    private val hideNote = Runnable { note.visibility = View.GONE }

    /** Shows [status]; with [announce], a new outcome appears above the button. */
    fun show(status: CallStatus, announce: Boolean = true) {
        val (text, color) = when (status.state) {
            CallState.IDLE -> R.string.call_start to START
            CallState.STARTING -> R.string.call_connecting to WAIT
            CallState.ACTIVE -> R.string.call_end to END
        }
        button.setText(text)
        button.background = pill(color)
        button.visibility = if (status.state == CallState.IDLE && status.unavailable != null) View.GONE else View.VISIBLE
        if (announce && status.feedback != null && status.feedback != shownFeedback) {
            note.text = status.feedback
            note.visibility = View.VISIBLE
            note.removeCallbacks(hideNote)
            note.postDelayed(hideNote, NOTE_MS)
        }
        shownFeedback = status.feedback
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        cornerRadius = dp(24).toFloat()
        setColor(color)
    }

    private fun dp(value: Int) = (value * density).toInt()

    private companion object {
        const val NOTE_MS = 5000L
        val START = Color.rgb(0x1f, 0x6f, 0x4a)
        val WAIT = Color.rgb(0x8a, 0x5a, 0x00)
        val END = Color.rgb(0x9b, 0x23, 0x23)
        val NOTE = Color.rgb(0x3a, 0x40, 0x4c)
    }
}

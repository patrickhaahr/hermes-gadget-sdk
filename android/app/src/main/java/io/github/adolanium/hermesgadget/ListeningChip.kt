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
 * The microphone's state over the face, top centre: listening, off, recording
 * or paused. Tapping it turns the microphone off, or on again. A detection
 * shows "Heard “Hey Hermes”" for a few seconds below it.
 */
class ListeningChip(context: Context, private val onToggle: (enable: Boolean) -> Unit) {
    private val density = context.resources.displayMetrics.density
    private var microphoneOff = false
    private var shownDetections = -1 // until the first status, any count is old news

    val chip = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(Color.WHITE)
        setPadding(dp(10), dp(4), dp(10), dp(4))
        minHeight = dp(28)
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setOnClickListener { onToggle(microphoneOff) }
        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(2) }
    }

    val banner = TextView(context).apply {
        setText(R.string.wake_heard)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        setTextColor(Color.WHITE)
        setPadding(dp(20), dp(10), dp(20), dp(10))
        background = pill(HEARD)
        visibility = View.GONE
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(56) }
    }

    private val hideBanner = Runnable { banner.visibility = View.GONE }

    /** Shows [status]; with [announce], a detection since the last call shows the banner. */
    fun show(status: AudioStatus, announce: Boolean = true) {
        microphoneOff = status.state == AudioState.MICROPHONE_OFF
        val (text, color) = when (status.state) {
            AudioState.WAKE_LISTENING -> R.string.wake_listening to LISTENING
            AudioState.MICROPHONE_OFF -> R.string.wake_microphone_off to OFF
            AudioState.GADGET_CAPTURE -> R.string.wake_recording to RECORDING
            AudioState.GADGET_PLAYBACK -> R.string.wake_paused to IDLE
            AudioState.HANDED_OFF -> R.string.wake_handed_off to RECORDING
            AudioState.WAKE_UNAVAILABLE -> R.string.wake_unavailable to IDLE
        }
        chip.setText(text)
        chip.background = pill(color)
        chip.contentDescription = chip.context.getString(text) + ". " +
            chip.context.getString(if (microphoneOff) R.string.wake_toggle_on else R.string.wake_toggle_off)
        if (announce && shownDetections >= 0 && status.detections > shownDetections) {
            banner.visibility = View.VISIBLE
            banner.removeCallbacks(hideBanner)
            banner.postDelayed(hideBanner, BANNER_MS)
        }
        shownDetections = status.detections
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        setColor(color)
    }

    private fun dp(value: Int) = (value * density).toInt()

    private companion object {
        const val BANNER_MS = 3000L
        val LISTENING = Color.rgb(0x1f, 0x6f, 0x4a)
        val OFF = Color.rgb(0x9b, 0x23, 0x23)
        val RECORDING = Color.rgb(0x8a, 0x5a, 0x00)
        val IDLE = Color.rgb(0x3a, 0x40, 0x4c)
        val HEARD = Color.rgb(0x25, 0x5d, 0xa8)
    }
}

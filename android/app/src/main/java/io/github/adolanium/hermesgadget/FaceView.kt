package io.github.adolanium.hermesgadget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import java.nio.ShortBuffer

/**
 * Draws the core's framebuffer scaled up with square pixels, letterboxed, and
 * turns touches into the core's screen coordinates.
 */
class FaceView(context: Context) : View(context) {
    private var bitmap: Bitmap? = null
    private val dst = Rect()
    private val paint = Paint().apply { isFilterBitmap = false }

    init {
        setBackgroundColor(Color.BLACK)
    }

    override fun onDraw(canvas: Canvas) {
        val frame = GadgetRuntime.core?.frame ?: return
        val bmp = bitmap?.takeIf { it.width == frame.width && it.height == frame.height }
            ?: Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.RGB_565).also { bitmap = it }
        synchronized(frame) { bmp.copyPixelsFromBuffer(ShortBuffer.wrap(frame.pixels)) }
        val scale = minOf(width / frame.width, height / frame.height).coerceAtLeast(1)
        val w = frame.width * scale
        val h = frame.height * scale
        dst.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        canvas.drawBitmap(bmp, null, dst, paint)
    }

    @SuppressLint("ClickableViewAccessibility") // The whole screen is the talk control, as on touch boards.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val core = GadgetRuntime.core ?: return false
        if (dst.isEmpty) return false
        val x = ((event.x - dst.left) * core.frame.width / dst.width()).toInt().coerceIn(0, core.frame.width - 1)
        val y = ((event.y - dst.top) * core.frame.height / dst.height()).toInt().coerceIn(0, core.frame.height - 1)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> core.touch(true, x, y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> core.touch(false, x, y)
        }
        return true
    }
}

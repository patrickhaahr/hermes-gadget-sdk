package io.github.adolanium.hermesgadget

import java.nio.ByteBuffer

/** The hgsim C ABI (firmware/sim/include/hgsim.h) through hgjni.cpp. Call only from the core thread. */
object NativeCore {
    const val ABI_VERSION = 5

    const val BUTTON_TALK = 0
    const val BUTTON_CANCEL = 1

    init {
        System.loadLibrary("hgjni")
    }

    external fun abiVersion(): Int

    external fun create(
        host: NativeHost, width: Int, height: Int, hasMic: Boolean, hasSpeaker: Boolean, micRate: Int,
        speakerRate: Int, board: ByteArray, firmware: ByteArray, defaultName: ByteArray, talkLabel: ByteArray?,
        cancelLabel: ByteArray?, touch: Boolean,
    ): Long

    external fun destroy(handle: Long)
    external fun begin(handle: Long)
    external fun tick(handle: Long)
    external fun network(handle: Long, up: Boolean, detail: ByteArray)
    external fun transportOpen(handle: Long)
    external fun transportText(handle: Long, data: ByteArray)
    external fun transportBinary(handle: Long, data: ByteArray)
    external fun transportClosed(handle: Long, reason: ByteArray)
    external fun button(handle: Long, button: Int, pressed: Boolean)
    external fun touch(handle: Long, touching: Boolean, x: Int, y: Int)
    external fun micSamples(handle: Long, samples: ShortArray, count: Int)
    external fun submitText(handle: Long, text: ByteArray)
    external fun setSensor(handle: Long, name: ByteArray, value: Double)
    external fun console(handle: Long, line: ByteArray): ByteArray
    external fun status(handle: Long): ByteArray
    external fun screen(handle: Long): ByteArray
    external fun framebuffer(handle: Long): ByteBuffer?
}

/**
 * The drivers the core calls, on the core thread. Strings are UTF-8 bytes (see hgjni.cpp).
 * Method names and signatures are looked up by hgjni.cpp; keep them in step.
 */
interface NativeHost {
    fun transportConnect(url: ByteArray, subprotocol: ByteArray)
    fun transportSendText(data: ByteArray): Boolean
    fun transportSendBinary(data: ByteArray): Boolean
    fun transportClose()
    fun displayFlush(y0: Int, y1: Int)
    fun displayBacklight(percent: Int)
    fun micStart(rate: Int): Boolean
    fun micStop()
    fun speakerBegin(rate: Int): Boolean
    fun speakerWrite(samples: ShortArray)
    fun speakerEnd()
    fun speakerAbort()
    fun speakerBusy(): Boolean
    fun speakerVolume(percent: Int)
    fun storageGet(key: ByteArray): ByteArray?
    fun storageSet(key: ByteArray, value: ByteArray)
    fun storageErase(key: ByteArray)
    fun nowMs(): Long
    fun randomBytes(count: Int): ByteArray
    fun log(level: Int, message: ByteArray)
}

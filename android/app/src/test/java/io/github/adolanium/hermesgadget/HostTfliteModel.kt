package io.github.adolanium.hermesgadget

import org.junit.Assume.assumeTrue
import java.io.File

/**
 * A wake model run by the TensorFlow Lite C library on the build machine
 * (through hgtflite, built from src/test/cpp), standing in for LiteRT so the
 * host tests run the production [WakeDetector] with the real models.
 */
class HostTfliteModel private constructor(private var handle: Long) : WakeModel {
    override fun resizeInput(shape: IntArray) = check(nativeResize(handle, shape)) { "resize failed" }
    override val inputShape: IntArray get() = nativeInputShape(handle)
    override val outputSize: Int get() = nativeOutputSize(handle)
    override fun run(input: FloatArray, output: FloatArray) = check(nativeRun(handle, input, output)) { "inference failed" }

    override fun close() {
        if (handle != 0L) nativeClose(handle)
        handle = 0L
    }

    companion object {
        private val library = File(System.getProperty("hg.tflite.library").orEmpty())
        private val models = File(System.getProperty("hg.wake.models").orEmpty())

        /** The bundled models; skips the test where the build couldn't fetch a host runtime (only Linux x86-64 has one). */
        fun models(): WakeModels {
            assumeTrue("no TensorFlow Lite C library for this machine at $library", library.isFile)
            System.loadLibrary("hgtflite")
            return WakeModels { name ->
                val handle = nativeOpen(library.path, File(models, name).readBytes())
                check(handle != 0L) { "$name did not load with $library" }
                HostTfliteModel(handle)
            }
        }

        @JvmStatic private external fun nativeOpen(library: String, model: ByteArray): Long
        @JvmStatic private external fun nativeResize(handle: Long, shape: IntArray): Boolean
        @JvmStatic private external fun nativeInputShape(handle: Long): IntArray
        @JvmStatic private external fun nativeOutputSize(handle: Long): Int
        @JvmStatic private external fun nativeRun(handle: Long, input: FloatArray, output: FloatArray): Boolean
        @JvmStatic private external fun nativeClose(handle: Long)
    }
}

/** The recorded fixtures, from src/wakeFixtures. */
object HostFixtures {
    private val dir = File(System.getProperty("hg.wake.fixtures").orEmpty())

    fun pcm(name: String) = WakeFixtures.pcm(File(dir, name).readBytes())

    val expected by lazy { WakeFixtures.expected(File(dir, "expected.txt").readText()) }
}

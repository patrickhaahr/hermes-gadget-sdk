package io.github.adolanium.hermesgadget

import android.content.res.AssetManager
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** A wake model run by LiteRT on the phone's CPU, one thread. */
class LiteRtModel(model: ByteBuffer) : WakeModel {
    private val interpreter = Interpreter(model, Interpreter.Options().setNumThreads(1))
    // Sized once the tensors are allocated: a dynamic input has no size until it is resized.
    private var input: FloatBuffer? = null
    private var output: FloatBuffer? = null

    override fun resizeInput(shape: IntArray) {
        interpreter.resizeInput(0, shape)
        input = null
        output = null
    }

    override val inputShape: IntArray get() = interpreter.getInputTensor(0).shape()
    override val outputSize: Int get() = allocated().second.capacity()

    override fun run(input: FloatArray, output: FloatArray) {
        val (inBuf, outBuf) = allocated()
        inBuf.rewind()
        inBuf.put(input)
        inBuf.rewind()
        outBuf.rewind()
        interpreter.run(inBuf, outBuf)
        outBuf.rewind()
        outBuf.get(output)
    }

    private fun allocated(): Pair<FloatBuffer, FloatBuffer> {
        val i = input
        val o = output
        if (i != null && o != null) return i to o
        interpreter.allocateTensors()
        return (floats(interpreter.getInputTensor(0).numElements()) to floats(interpreter.getOutputTensor(0).numElements()))
            .also { (a, b) ->
                input = a
                output = b
            }
    }

    override fun close() = interpreter.close()

    companion object {
        /** The bundled models, under assets/wake/. */
        fun fromAssets(assets: AssetManager) = WakeModels { name ->
            val bytes = assets.open("wake/$name").use { it.readBytes() }
            LiteRtModel(ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).apply { rewind() })
        }

        private fun floats(count: Int): FloatBuffer =
            ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    }
}

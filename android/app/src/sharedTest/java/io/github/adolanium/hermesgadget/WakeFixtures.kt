package io.github.adolanium.hermesgadget

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The recorded wake fixtures (src/wakeFixtures) and the reference engine's
 * results for them, read the same way by the host tests and the phone tests.
 */
object WakeFixtures {
    const val POSITIVE_LJSPEECH = "hey_hermes_ljspeech.wav"
    const val POSITIVE_LIBRITTS = "hey_hermes_libritts.wav"
    const val UNRELATED = "unrelated_speech.wav"
    const val NEAR_MISS = "near_miss.wav"
    const val REQUEST = "wake_request.wav"
    val ALL = listOf(REQUEST, POSITIVE_LJSPEECH, POSITIVE_LIBRITTS, UNRELATED, NEAR_MISS)

    class Expected(val detections: List<Int>, val scores: List<Float?>)

    /** 16 kHz mono PCM16 samples from a canonical WAV file. */
    fun pcm(wav: ByteArray): ShortArray {
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        check(String(wav, 0, 4) == "RIFF" && String(wav, 8, 4) == "WAVE") { "not a WAV file" }
        var at = 12
        while (at + 8 <= wav.size) {
            val id = String(wav, at, 4)
            val size = b.getInt(at + 4)
            if (id == "fmt ") {
                check(b.getShort(at + 8).toInt() == 1 && b.getShort(at + 10).toInt() == 1 &&
                    b.getInt(at + 12) == WakeDetector.SAMPLE_RATE && b.getShort(at + 22).toInt() == 16) { "not 16 kHz mono PCM16" }
            } else if (id == "data") {
                val out = ShortArray(size / 2)
                b.position(at + 8)
                b.asShortBuffer().get(out)
                return out
            }
            at += 8 + size + (size and 1)
        }
        error("no data chunk")
    }

    fun expected(text: String): Map<String, Expected> {
        val fields = text.lines().filter { it.isNotBlank() && !it.startsWith("#") }.associate { line ->
            val (name, rest) = line.split(" ", limit = 2)
            val (key, value) = rest.split("=", limit = 2)
            (name to key) to value
        }
        return fields.keys.map { it.first }.distinct().associateWith { name ->
            Expected(
                fields.getValue(name to "detections").split(",").filter { it.isNotEmpty() }.map(String::toInt),
                fields.getValue(name to "scores").split(" ").map { if (it == "-") null else it.toFloat() },
            )
        }
    }

    /** Runs [pcm] through [detector] chunk by chunk, resetting after each detection as the app does. */
    fun run(detector: WakeDetector, pcm: ShortArray, onChunk: (index: Int, score: Float) -> Unit = { _, _ -> }): List<Int> {
        val detections = mutableListOf<Int>()
        for (i in 0 until pcm.size / WakeDetector.CHUNK_SAMPLES) {
            val chunk = pcm.copyOfRange(i * WakeDetector.CHUNK_SAMPLES, (i + 1) * WakeDetector.CHUNK_SAMPLES)
            val fired = detector.process(chunk)
            onChunk(i, detector.lastScore)
            if (fired) {
                detections += i
                detector.reset()
            }
        }
        return detections
    }
}

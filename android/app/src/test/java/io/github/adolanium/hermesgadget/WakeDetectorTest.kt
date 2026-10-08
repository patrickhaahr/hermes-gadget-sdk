package io.github.adolanium.hermesgadget

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The production wake pipeline with the real melspectrogram, embedding and
 * hey_hermes models, against recorded speech and the reference engine's
 * results for it (android/tools/wake_fixtures.py). The runtime here is the
 * TensorFlow Lite C library; the phone's LiteRT runs the same fixtures in
 * WakeDetectorDeviceTest.
 */
class WakeDetectorTest {
    private lateinit var detector: WakeDetector

    @Before
    fun open() {
        detector = WakeDetector(HostTfliteModel.models())
    }

    @After
    fun close() = detector.close()

    @Test
    fun scoresEachChunkAsTheReferenceEngineDoes() {
        for (name in WakeFixtures.ALL) {
            val expected = HostFixtures.expected.getValue(name)
            val scores = mutableListOf<Float>()
            val detections = WakeFixtures.run(detector.apply { reset() }, HostFixtures.pcm(name)) { _, score -> scores += score }
            assertEquals(name, expected.detections, detections)
            assertEquals(name, expected.scores.size, scores.size)
            for ((i, score) in scores.withIndex()) {
                assertEquals("$name chunk $i", expected.scores[i] ?: Float.NaN, score, SCORE_TOLERANCE)
            }
        }
    }

    @Test
    fun hearsTheWakePhraseOncePerUtterance() {
        for (name in listOf(WakeFixtures.POSITIVE_LJSPEECH, WakeFixtures.POSITIVE_LIBRITTS)) {
            assertEquals(name, 1, WakeFixtures.run(detector.apply { reset() }, HostFixtures.pcm(name)).size)
        }
    }

    @Test
    fun ignoresOtherSpeech() {
        for (name in listOf(WakeFixtures.UNRELATED, WakeFixtures.NEAR_MISS)) {
            assertEquals(name, emptyList<Int>(), WakeFixtures.run(detector.apply { reset() }, HostFixtures.pcm(name)))
        }
    }

    @Test
    fun rearmsAfterADetection() {
        val phrase = HostFixtures.pcm(WakeFixtures.POSITIVE_LJSPEECH)
        val other = HostFixtures.pcm(WakeFixtures.POSITIVE_LIBRITTS)
        val detections = WakeFixtures.run(detector, phrase + other + phrase)
        assertEquals(3, detections.size)
    }

    @Test
    fun aResetForgetsAPartlyHeardPhrase() {
        val phrase = HostFixtures.pcm(WakeFixtures.POSITIVE_LJSPEECH)
        // Split one chunk before the detection: the phrase is heard but not yet scored.
        val split = HostFixtures.expected.getValue(WakeFixtures.POSITIVE_LJSPEECH).detections.single() * WakeDetector.CHUNK_SAMPLES
        val head = phrase.copyOfRange(0, split)
        val tail = phrase.copyOfRange(split, phrase.size)
        assertEquals(emptyList<Int>(), WakeFixtures.run(detector, head))
        assertEquals("without a reset the rest completes it", 1, WakeFixtures.run(detector, tail).size)

        detector.reset()
        assertEquals(emptyList<Int>(), WakeFixtures.run(detector, head))
        detector.reset()
        assertEquals(emptyList<Int>(), WakeFixtures.run(detector, tail))
    }

    private companion object {
        // Same models and runtime as the reference; only float rounding may differ.
        const val SCORE_TOLERANCE = 1e-3f
    }
}

package io.github.adolanium.hermesgadget

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * The wake pipeline as the app runs it: the APK's bundled models on LiteRT,
 * on the phone. The fixtures and the reference engine's results are the host
 * tests' (src/wakeFixtures). Detection outcomes must match; how far the scores
 * drift from the reference is logged (tag HermesWakeTest), not asserted.
 *
 *     ./gradlew assembleDebug assembleDebugAndroidTest
 *     adb install -r app/build/outputs/apk/debug/app-debug.apk
 *     adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 *     adb shell am instrument -w io.github.adolanium.hermesgadget.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Instrumentation restarts the app's process; its data is kept.
 */
@RunWith(AndroidJUnit4::class)
class WakeDetectorDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var detector: WakeDetector

    @Before
    fun open() {
        detector = WakeDetector(LiteRtModel.fromAssets(instrumentation.targetContext.assets))
    }

    @After
    fun close() = detector.close()

    private fun fixture(name: String) = instrumentation.context.assets.open(name).use { it.readBytes() }

    private val expected by lazy { WakeFixtures.expected(fixture("expected.txt").decodeToString()) }

    @Test
    fun detectsAsTheReferenceEngineDoes() {
        for (name in WakeFixtures.ALL) {
            val reference = expected.getValue(name)
            var drift = 0f
            var peak = 0f
            val started = System.nanoTime()
            val pcm = WakeFixtures.pcm(fixture(name))
            val detections = WakeFixtures.run(detector.apply { reset() }, pcm) { i, score ->
                reference.scores[i]?.let { drift = maxOf(drift, abs(it - score)) }
                if (!score.isNaN()) peak = maxOf(peak, score)
            }
            val ms = (System.nanoTime() - started) / 1_000_000
            Log.i(TAG, "%s: detections %s (reference %s), peak %.4f, max drift from reference %.5f, %d ms for %d ms of audio"
                .format(name, detections, reference.detections, peak, drift, ms, pcm.size * 1000 / WakeDetector.SAMPLE_RATE))
            assertEquals(name, reference.detections, detections)
        }
    }

    @Test
    fun rearmsAfterADetectionAndForgetsOnReset() {
        val phrase = WakeFixtures.pcm(fixture(WakeFixtures.POSITIVE_LJSPEECH))
        assertEquals(2, WakeFixtures.run(detector, phrase + phrase).size)
        val split = expected.getValue(WakeFixtures.POSITIVE_LJSPEECH).detections.single() * WakeDetector.CHUNK_SAMPLES
        val head = phrase.copyOfRange(0, split)
        val tail = phrase.copyOfRange(split, phrase.size)
        detector.reset()
        assertEquals(emptyList<Int>(), WakeFixtures.run(detector, head))
        assertEquals("without a reset the rest completes it", 1, WakeFixtures.run(detector, tail).size)
        detector.reset()
        assertEquals(emptyList<Int>(), WakeFixtures.run(detector, head))
        detector.reset()
        assertEquals(emptyList<Int>(), WakeFixtures.run(detector, tail))
    }

    private companion object {
        const val TAG = "HermesWakeTest"
    }
}

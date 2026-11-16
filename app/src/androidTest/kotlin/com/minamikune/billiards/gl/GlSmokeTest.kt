package com.minamikune.billiards.gl

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Roadmap §7.1 GL smoke gate, wired to the real renderer: at least 60 frames
 * must render with zero glGetError results, and a mid-run memory trim must be
 * recovered lazily on the next frame instead of black-screening (risk R22).
 */
@RunWith(AndroidJUnit4::class)
class GlSmokeTest {
    @Test
    fun rendersSixtyFramesWithoutGlErrorsAndSurvivesTrim() {
        ActivityScenario.launch(GlSmokeActivity::class.java).use { scenario ->
            waitUntil("60 frames rendered") { poll(scenario).first >= 60L }
            val (frames, errors) = poll(scenario)
            assertTrue("expected >= 60 frames, got $frames", frames >= 60L)
            assertEquals("GL errors during $frames frames", 0, errors)

            scenario.onActivity { activity ->
                activity.glView.queueEvent { activity.renderer.trimResources() }
            }
            val frameAtTrim = poll(scenario).first
            waitUntil("renderer recovers after trim") { poll(scenario).first >= frameAtTrim + 10L }
            val (afterTrimFrames, afterTrimErrors) = poll(scenario)
            assertTrue(
                "frames must keep advancing after trim (at $frameAtTrim, now $afterTrimFrames)",
                afterTrimFrames >= frameAtTrim + 10L,
            )
            assertEquals("GL errors after trim recovery", 0, afterTrimErrors)
        }
    }

    private fun poll(scenario: ActivityScenario<GlSmokeActivity>): Pair<Long, Int> {
        val frames = AtomicLong(-1L)
        val errors = AtomicInteger(-1)
        scenario.onActivity { activity ->
            frames.set(activity.renderer.framesRendered)
            errors.set(activity.renderer.glErrorCount)
        }
        return frames.get() to errors.get()
    }

    private fun waitUntil(what: String, timeoutMs: Long = 30_000L, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) {
                throw AssertionError("Timed out waiting for: $what")
            }
            Thread.sleep(50L)
        }
    }
}

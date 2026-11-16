package com.minamikune.billiards.haptics

import com.minamikune.billiards.physics.PhysicsEvent
import com.minamikune.billiards.physics.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * The physics-event → vibration mapping: below-threshold silence, impact
 * scaling by the user strength setting, waveform shapes and cooldowns are
 * all behavioural contract.
 */
class HapticPatternsTest {
    private val origin = Vec2(0.0, 0.0)
    private val strength = 0.8f
    private fun scaled(value: Double): Int = (value * strength).roundToInt().coerceIn(1, 255)

    @Test
    fun subThresholdBallHitsStaySilent() {
        assertNull(HapticPatterns.forEvent(PhysicsEvent.BallHit(0, 1, 0.27, origin), strength))
        assertNull(HapticPatterns.forEvent(PhysicsEvent.BallHit(0, 1, 0.1, origin), strength))
    }

    @Test
    fun lightBallHitUsesShortSoftPulse() {
        val plan = HapticPatterns.forEvent(PhysicsEvent.BallHit(0, 1, 0.6, origin), strength)!!
        assertEquals("ball_light", plan.key)
        assertEquals(45L, plan.cooldownMs)
        val one = plan.effect as HapticEffect.OneShot
        assertEquals(7L, one.ms)
        assertEquals(scaled(45 + 0.6 * 30), one.amplitude)
    }

    @Test
    fun hardBallHitBoundaryAt1_2() {
        val justAbove = HapticPatterns.forEvent(PhysicsEvent.BallHit(0, 1, 1.2, origin), strength)!!
        assertEquals("ball_hard", justAbove.key)
        val one = justAbove.effect as HapticEffect.OneShot
        assertEquals(15L, one.ms)
        assertEquals(scaled(115 + 1.2 * 28), one.amplitude)
    }

    @Test
    fun subThresholdCushionStaysSilent() {
        assertNull(HapticPatterns.forEvent(PhysicsEvent.CushionHit(0, 0.3, origin), strength))
        val hit = HapticPatterns.forEvent(PhysicsEvent.CushionHit(0, 1.0, origin), strength)!!
        assertEquals("cushion", hit.key)
        val one = hit.effect as HapticEffect.OneShot
        assertEquals(9L, one.ms)
        assertEquals(scaled(55 + 1.0 * 22), one.amplitude)
    }

    @Test
    fun cueStruckScalesWithShotSpeed() {
        val plan = HapticPatterns.forEvent(PhysicsEvent.CueStruck(4.5), strength)!!
        assertEquals("cue", plan.key)
        assertEquals(35L, plan.cooldownMs)
        val one = plan.effect as HapticEffect.OneShot
        assertEquals(11L, one.ms)
        assertEquals(scaled(80 + 4.5 / 9.0 * 150), one.amplitude)
    }

    @Test
    fun pocketUsesHeavyOneShot() {
        val plan = HapticPatterns.forEvent(PhysicsEvent.Pocketed(0, 0, origin), strength)!!
        assertEquals("pocket", plan.key)
        assertEquals(120L, plan.cooldownMs)
        val one = plan.effect as HapticEffect.OneShot
        assertEquals(22L, one.ms)
        assertEquals(scaled(155.0), one.amplitude)
    }

    @Test
    fun foulUsesDoublePulseWaveform() {
        val plan = HapticPatterns.forEvent(PhysicsEvent.Foul, strength)!!
        assertEquals("foul", plan.key)
        assertEquals(400L, plan.cooldownMs)
        val wave = plan.effect as HapticEffect.Waveform
        assertEquals(listOf(0L, 22L, 38L, 30L), wave.timings)
        assertEquals(listOf(0, 140, 0, 95), wave.amplitudes)
    }

    @Test
    fun winAndLoseWaveformsDiffer() {
        val win = HapticPatterns.forEvent(PhysicsEvent.MatchWon(true), strength)!!
        val lose = HapticPatterns.forEvent(PhysicsEvent.MatchWon(false), strength)!!
        assertEquals("win", win.key)
        assertEquals("lose", lose.key)
        assertEquals(1000L, win.cooldownMs)
        val w = win.effect as HapticEffect.Waveform
        assertEquals(listOf(0L, 26L, 55L, 26L, 55L, 70L), w.timings)
        val l = lose.effect as HapticEffect.Waveform
        assertEquals(listOf(0L, 55L, 60L, 90L), l.timings)
    }

    @Test
    fun settledProducesNothing() {
        assertNull(HapticPatterns.forEvent(PhysicsEvent.Settled, strength))
    }

    @Test
    fun amplitudeNeverExceedsVibratorRange() {
        // Even a monstrous impact at full strength clamps to 255, and the
        // smallest legal amplitude clamps to 1 (a zero amplitude would read
        // as "silence" to VibrationEffect).
        val huge = HapticPatterns.forEvent(PhysicsEvent.BallHit(0, 1, 500.0, origin), 1.0f)!!
        val one = huge.effect as HapticEffect.OneShot
        assertEquals(255, one.amplitude)
        val tiny = HapticPatterns.forEvent(PhysicsEvent.CushionHit(0, 0.4, origin), 0.2f)!!
        val tinyOne = tiny.effect as HapticEffect.OneShot
        assertTrue("amplitude must be >= 1, got ${tinyOne.amplitude}", tinyOne.amplitude >= 1)
    }

    @Test
    fun defaultAmplitudeMarkerIsMinusOne() {
        // Mirrors VibrationEffect.DEFAULT_AMPLITUDE so the Android adapter can
        // pass it through verbatim for devices without amplitude control.
        assertEquals(-1, HapticPatterns.DEFAULT_AMPLITUDE)
    }
}

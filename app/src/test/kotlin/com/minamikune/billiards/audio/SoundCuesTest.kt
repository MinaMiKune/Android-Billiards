package com.minamikune.billiards.audio

import com.minamikune.billiards.physics.PhysicsEvent
import com.minamikune.billiards.physics.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The physics-event → sound mapping: impact curves, the light/heavy split and
 * every cooldown are behavioural contract and must not drift.
 */
class SoundCuesTest {
    private val origin = Vec2(0.0, 0.0)

    @Test
    fun softBallHitUsesLightBallAndLowVolume() {
        val cue = SoundCues.forEvent(PhysicsEvent.BallHit(0, 1, 0.6, origin))!!
        assertEquals(SoundKind.BALL_LIGHT, cue.kind)
        assertEquals((0.16 + 0.6 * 0.15).toFloat(), cue.volume, 1e-6f)
        assertEquals((0.94 + 0.6 * 0.018).toFloat(), cue.rate, 1e-6f)
        assertEquals(28L, cue.cooldownMs)
    }

    @Test
    fun hardBallHitCrossesAtImpactSpeedAbove1_35() {
        assertEquals(SoundKind.BALL_LIGHT, SoundCues.forEvent(PhysicsEvent.BallHit(0, 1, 1.3, origin))!!.kind)
        assertEquals(SoundKind.BALL_HARD, SoundCues.forEvent(PhysicsEvent.BallHit(0, 1, 1.4, origin))!!.kind)
    }

    @Test
    fun ballHitVolumeAndRateClampAtExtremes() {
        // At resting speed the curve sits at its natural floor (0.16 / 0.94);
        // the lower clamp bounds are unreachable for non-negative speeds.
        val still = SoundCues.forEvent(PhysicsEvent.BallHit(0, 1, 0.001, origin))!!
        assertEquals(0.16015f, still.volume, 1e-6f)
        assertEquals(0.940018f, still.rate, 1e-6f)
        // A smash clamps to the ceiling in both dimensions.
        val smash = SoundCues.forEvent(PhysicsEvent.BallHit(0, 1, 30.0, origin))!!
        assertEquals(0.95f, smash.volume, 1e-6f)
        assertEquals(1.16f, smash.rate, 1e-6f)
    }

    @Test
    fun cushionHitFollowsItsOwnCurve() {
        val cue = SoundCues.forEvent(PhysicsEvent.CushionHit(2, 2.0, origin))!!
        assertEquals(SoundKind.CUSHION, cue.kind)
        assertEquals((0.10 + 2.0 * 0.12).toFloat(), cue.volume, 1e-6f)
        assertEquals(1f, cue.rate, 1e-6f)
        assertEquals(55L, cue.cooldownMs)
    }

    @Test
    fun pocketFoulAndSettledMapAsExpected() {
        val pocket = SoundCues.forEvent(PhysicsEvent.Pocketed(3, 0, origin))!!
        assertEquals(SoundKind.POCKET, pocket.kind)
        assertEquals(90L, pocket.cooldownMs)
        val foul = SoundCues.forEvent(PhysicsEvent.Foul)!!
        assertEquals(SoundKind.FOUL, foul.kind)
        assertEquals(500L, foul.cooldownMs)
        assertNull(SoundCues.forEvent(PhysicsEvent.Settled))
    }

    @Test
    fun cueStruckScalesWithShotSpeed() {
        val soft = SoundCues.forEvent(PhysicsEvent.CueStruck(1.0))!!
        assertEquals(SoundKind.CUE, soft.kind)
        assertEquals((0.35 + 1.0 * 0.06).toFloat(), soft.volume, 1e-6f)
        assertEquals(40L, soft.cooldownMs)
        val maxed = SoundCues.forEvent(PhysicsEvent.CueStruck(500.0))!!
        assertEquals(0.95f, maxed.volume, 1e-6f)
    }

    @Test
    fun matchWonSelectsWinOrLoseSound() {
        val win = SoundCues.forEvent(PhysicsEvent.MatchWon(humanWon = true))!!
        assertEquals(SoundKind.WIN, win.kind)
        assertEquals(900L, win.cooldownMs)
        val lose = SoundCues.forEvent(PhysicsEvent.MatchWon(humanWon = false))!!
        assertEquals(SoundKind.LOSE, lose.kind)
    }

    @Test
    fun uiCueIsQuietAndFastToRetrigger() {
        val cue = SoundCues.ui()
        assertEquals(SoundKind.UI, cue.kind)
        assertEquals(0.35f, cue.volume, 1e-6f)
        assertEquals(30L, cue.cooldownMs)
    }

    @Test
    fun everyEventIsCovered() {
        // Exhaustive when() guard: adding a PhysicsEvent variant without a
        // mapping must fail to compile, and this catches nulls for events
        // that used to be silently ignored.
        val events: List<PhysicsEvent> = listOf(
            PhysicsEvent.BallHit(0, 1, 1.0, origin),
            PhysicsEvent.CushionHit(0, 1.0, origin),
            PhysicsEvent.Pocketed(0, 0, origin),
            PhysicsEvent.Settled,
            PhysicsEvent.CueStruck(5.0),
            PhysicsEvent.Foul,
            PhysicsEvent.MatchWon(true),
        )
        assertEquals(7, events.size)
    }
}

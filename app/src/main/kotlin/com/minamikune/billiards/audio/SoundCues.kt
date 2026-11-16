package com.minamikune.billiards.audio

import com.minamikune.billiards.physics.PhysicsEvent

/** Identity of one decoded sound resource in the bank. */
enum class SoundKind { CUE, BALL_LIGHT, BALL_HARD, CUSHION, POCKET, FOUL, WIN, LOSE, UI }

/**
 * A fully resolved playback request: which sound, at what volume and rate,
 * guarded by which per-sound cooldown.
 */
data class SoundCue(
    val kind: SoundKind,
    val volume: Float,
    val rate: Float,
    val cooldownMs: Long,
)

/**
 * Pure physics-event → sound mapping, extracted verbatim from SoundBank so the
 * volume/rate curves, the light/heavy impact split and every cooldown become
 * unit-testable. SoundBank only resolves [SoundKind] to SoundPool ids and
 * performs rate-limited playback.
 */
object SoundCues {
    fun forEvent(event: PhysicsEvent): SoundCue? = when (event) {
        is PhysicsEvent.BallHit -> {
            val speed = event.impactSpeed
            SoundCue(
                kind = if (speed > 1.35) SoundKind.BALL_HARD else SoundKind.BALL_LIGHT,
                volume = (0.16 + speed * 0.15).toFloat().coerceIn(0.12f, 0.95f),
                rate = (0.94 + speed * 0.018).toFloat().coerceIn(0.9f, 1.16f),
                cooldownMs = 28,
            )
        }
        is PhysicsEvent.CushionHit -> SoundCue(
            kind = SoundKind.CUSHION,
            volume = (0.10 + event.impactSpeed * 0.12).toFloat().coerceIn(0.08f, 0.65f),
            rate = 1f,
            cooldownMs = 55,
        )
        is PhysicsEvent.Pocketed -> SoundCue(SoundKind.POCKET, 0.85f, 1f, 90)
        is PhysicsEvent.CueStruck -> SoundCue(
            kind = SoundKind.CUE,
            volume = (0.35 + event.speed * 0.06).toFloat().coerceIn(0.3f, 0.95f),
            rate = 1f,
            cooldownMs = 40,
        )
        PhysicsEvent.Foul -> SoundCue(SoundKind.FOUL, 0.55f, 1f, 500)
        is PhysicsEvent.MatchWon -> SoundCue(
            kind = if (event.humanWon) SoundKind.WIN else SoundKind.LOSE,
            volume = 0.85f,
            rate = 1f,
            cooldownMs = 900,
        )
        PhysicsEvent.Settled -> null
    }

    fun ui(): SoundCue = SoundCue(SoundKind.UI, 0.35f, 1f, 30)
}

package com.minamikune.billiards.haptics

import com.minamikune.billiards.physics.PhysicsEvent
import kotlin.math.roundToInt

/**
 * Vibration request described without android.os classes so the mapping is
 * unit-testable on the JVM. An [amplitude] of [DEFAULT_AMPLITUDE] (-1) selects
 * the platform default amplitude, mirroring VibrationEffect.DEFAULT_AMPLITUDE.
 */
sealed interface HapticEffect {
    data class OneShot(val ms: Long, val amplitude: Int) : HapticEffect

    /** [amplitudes] == null selects the platform default amplitude per timing. */
    data class Waveform(val timings: List<Long>, val amplitudes: List<Int>?) : HapticEffect
}

/**
 * A planned vibration plus the cooldown key and window that guard it against
 * retrigger spam. The key mirrors the per-event throttling of the original
 * Haptics implementation (light and hard ball hits throttle independently).
 */
data class HapticPlan(
    val key: String,
    val effect: HapticEffect,
    val cooldownMs: Long,
)

/**
 * Pure physics-event → vibration mapping, extracted verbatim from Haptics:
 * impact thresholds (below-threshold taps stay silent), durations, waveform
 * shapes and per-event cooldowns are unchanged. Amplitudes are scaled by the
 * user strength setting and clamped to 1..255 exactly as before.
 */
object HapticPatterns {
    const val DEFAULT_AMPLITUDE: Int = -1

    fun forEvent(event: PhysicsEvent, strength: Float): HapticPlan? {
        fun amp(value: Double): Int = (value * strength).roundToInt().coerceIn(1, 255)
        return when (event) {
            is PhysicsEvent.CueStruck -> HapticPlan(
                key = "cue",
                effect = HapticEffect.OneShot(11, amp(80.0 + event.speed / 9.0 * 150.0)),
                cooldownMs = 35,
            )
            is PhysicsEvent.BallHit -> {
                val v = event.impactSpeed
                when {
                    v < 0.28 -> null
                    v < 1.2 -> HapticPlan(
                        key = "ball_light",
                        effect = HapticEffect.OneShot(7, amp(45.0 + v * 30.0)),
                        cooldownMs = 45,
                    )
                    else -> HapticPlan(
                        key = "ball_hard",
                        effect = HapticEffect.OneShot(15, amp(115.0 + v * 28.0)),
                        cooldownMs = 55,
                    )
                }
            }
            is PhysicsEvent.CushionHit -> {
                if (event.impactSpeed < 0.35) null
                else HapticPlan(
                    key = "cushion",
                    effect = HapticEffect.OneShot(9, amp(55.0 + event.impactSpeed * 22.0)),
                    cooldownMs = 65,
                )
            }
            is PhysicsEvent.Pocketed -> HapticPlan(
                key = "pocket",
                effect = HapticEffect.OneShot(22, amp(155.0)),
                cooldownMs = 120,
            )
            PhysicsEvent.Foul -> HapticPlan(
                key = "foul",
                effect = HapticEffect.Waveform(listOf(0, 22, 38, 30), listOf(0, 140, 0, 95)),
                cooldownMs = 400,
            )
            is PhysicsEvent.MatchWon -> if (event.humanWon) {
                HapticPlan(
                    key = "win",
                    effect = HapticEffect.Waveform(listOf(0, 26, 55, 26, 55, 70), listOf(0, 210, 0, 170, 0, 220)),
                    cooldownMs = 1000,
                )
            } else {
                HapticPlan(
                    key = "lose",
                    effect = HapticEffect.Waveform(listOf(0, 55, 60, 90), listOf(0, 150, 0, 90)),
                    cooldownMs = 1000,
                )
            }
            PhysicsEvent.Settled -> null
        }
    }
}

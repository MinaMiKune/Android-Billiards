package com.minamikune.billiards.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import com.minamikune.billiards.data.Prefs
import com.minamikune.billiards.physics.PhysicsEvent
import kotlin.math.roundToInt

/**
 * Centralised, defensive haptics. Every call is best-effort: a device without a
 * vibrator, a revoked permission, or a vendor bug must never interrupt gameplay.
 */
class Haptics(context: Context, private val prefs: Prefs) {
    private val appContext = context.applicationContext
    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = appContext.getSystemService(VibratorManager::class.java)
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (_: Throwable) {
        null
    }

    private val hasVibrator = try {
        vibrator?.hasVibrator() == true
    } catch (_: Throwable) {
        false
    }
    private val hasAmplitude = try {
        vibrator?.hasAmplitudeControl() == true
    } catch (_: Throwable) {
        false
    }

    private val lastEvent = HashMap<String, Long>()

    fun uiTap(view: View) {
        if (!prefs.hapticsEnabled) return
        try {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        } catch (_: Throwable) {
            Unit
        }
    }

    fun play(event: PhysicsEvent) {
        if (!prefs.hapticsEnabled || !hasVibrator) return
        when (event) {
            is PhysicsEvent.CueStruck -> {
                val amp = amp(80 + event.speed / 9.0 * 150)
                oneShot(11, amp, "cue", 35)
            }
            is PhysicsEvent.BallHit -> {
                val s = event.impactSpeed
                if (s < 0.28) return
                if (s < 1.2) {
                    oneShot(7, amp(45 + s * 30), "ball_light", 45)
                } else {
                    oneShot(15, amp(115 + s * 28), "ball_hard", 55)
                }
            }
            is PhysicsEvent.CushionHit -> {
                if (event.impactSpeed < 0.35) return
                oneShot(9, amp(55 + event.impactSpeed * 22), "cushion", 65)
            }
            is PhysicsEvent.Pocketed -> oneShot(22, amp(155.0), "pocket", 120)
            PhysicsEvent.Foul -> pattern(longArrayOf(0, 22, 38, 30), intArrayOf(0, 140, 0, 95), "foul", 400)
            is PhysicsEvent.MatchWon -> {
                if (event.humanWon) {
                    pattern(longArrayOf(0, 26, 55, 26, 55, 70), intArrayOf(0, 210, 0, 170, 0, 220), "win", 1000)
                } else {
                    pattern(longArrayOf(0, 55, 60, 90), intArrayOf(0, 150, 0, 90), "lose", 1000)
                }
            }
            PhysicsEvent.Settled -> Unit
        }
    }

    private fun amp(value: Double): Int {
        val scaled = value * prefs.hapticStrength
        return scaled.roundToInt().coerceIn(1, 255)
    }

    private fun oneShot(ms: Long, amplitude: Int, key: String, cooldown: Long) {
        if (throttled(key, cooldown)) return
        try {
            if (hasAmplitude) {
                vibrator?.vibrate(VibrationEffect.createOneShot(ms, amplitude))
            } else {
                vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (_: Throwable) {
            // Never let a vendor vibration failure reach the game loop.
        }
    }

    private fun pattern(timings: LongArray, amplitudes: IntArray, key: String, cooldown: Long) {
        if (throttled(key, cooldown)) return
        try {
            if (hasAmplitude) {
                vibrator?.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                vibrator?.vibrate(VibrationEffect.createWaveform(timings, -1))
            }
        } catch (_: Throwable) {
            Unit
        }
    }

    private fun throttled(key: String, cooldown: Long): Boolean {
        val now = System.nanoTime() / 1_000_000
        val last = lastEvent[key] ?: 0L
        if (now - last < cooldown) return true
        lastEvent[key] = now
        return false
    }
}

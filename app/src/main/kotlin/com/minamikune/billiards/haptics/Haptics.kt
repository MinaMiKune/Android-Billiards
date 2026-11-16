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
import com.minamikune.billiards.util.CooldownGate

/**
 * Centralised, defensive haptics. Every call is best-effort: a device without a
 * vibrator, a revoked permission, or a vendor bug must never interrupt gameplay.
 * Event selection/throttling lives in [HapticPatterns]; this class only
 * resolves the plans to VibrationEffect calls.
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

    private val gate = CooldownGate()

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
        val plan = HapticPatterns.forEvent(event, prefs.hapticStrength) ?: return
        if (!gate.tryAcquire(plan.key, plan.cooldownMs)) return
        try {
            when (val effect = plan.effect) {
                is HapticEffect.OneShot -> {
                    val amplitude = if (hasAmplitude) effect.amplitude else VibrationEffect.DEFAULT_AMPLITUDE
                    vibrator?.vibrate(VibrationEffect.createOneShot(effect.ms, amplitude))
                }
                is HapticEffect.Waveform -> {
                    if (hasAmplitude && effect.amplitudes != null) {
                        vibrator?.vibrate(
                            VibrationEffect.createWaveform(
                                effect.timings.toLongArray(),
                                effect.amplitudes.toIntArray(),
                                -1,
                            ),
                        )
                    } else {
                        vibrator?.vibrate(VibrationEffect.createWaveform(effect.timings.toLongArray(), -1))
                    }
                }
            }
        } catch (_: Throwable) {
            // Never let a vendor vibration failure reach the game loop.
        }
    }
}

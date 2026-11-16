package com.minamikune.billiards.ui

import com.minamikune.billiards.game.SessionState
import com.minamikune.billiards.physics.Vec2
import kotlin.math.hypot

/** Immutable slice of match state the touch layer is allowed to consult. */
data class HudTouchContext(
    val state: SessionState,
    val practice: Boolean,
    val currentPlayer: Int,
) {
    val humanTurn: Boolean get() = practice || currentPlayer == 0
    val canShoot: Boolean get() = state == SessionState.AIMING && humanTurn
}

/** Side effects the HUD touch layer requests; the View maps them to callbacks. */
sealed interface HudEffect {
    data class PowerChanged(val value: Double) : HudEffect
    data class SpinChanged(val side: Double, val vertical: Double) : HudEffect
    data class AimAt(val point: Vec2) : HudEffect
    data class PlaceCue(val point: Vec2) : HudEffect
    data object Shoot : HudEffect
    data object Pause : HudEffect
    data object FastForward : HudEffect
    data object UiTap : HudEffect
}

/** Momentary press targets shown depressed while held. */
enum class HudPress { PAUSE, FAST_FORWARD, SHOOT }

/**
 * Pure, pointer-aware touch state machine for the in-game HUD. The View feeds
 * pointer-scoped events and dispatches the returned effects.
 *
 * Behaviour carried over from the original all-in-View handler: hit-test order
 * (pause → fast-forward → shoot → power → spin → table), pause/fast-forward
 * fire on release regardless of where the finger lifts, shoot re-checks
 * `canShoot` against the latest snapshot, and the ball-in-hand placement uses
 * the snapshot state at release time.
 *
 * Deliberate fixes/improvements over the original single-flag handler:
 *  1. Every gesture is owned by the pointer that started it. With the legacy
 *     code, lifting *any* finger emitted ACTION_UP and tore down all drags, so
 *     a two-finger aim+power adjustment collapsed as soon as either finger
 *     released.
 *  2. The power slider tracks relative motion from the grab point instead of
 *     jumping to the raw finger position, so grabbing the bar away from the
 *     knob no longer snaps the power.
 *  3. The shoot button disarms if its pointer leaves the button before release
 *     (previously a press that slid off still struck the cue on release).
 */
class HudTouchController(
    private val layout: () -> HudLayout,
    private val context: () -> HudTouchContext,
    private val powerAnchor: () -> Float,
    private val aimMapper: (Float, Float) -> Vec2?,
) {
    private var aimPointer = INACTIVE
    private var powerPointer = INACTIVE
    private var spinPointer = INACTIVE
    private val presses = HashMap<Int, HudPress>()

    /** Grab point offset (in power fraction) for relative power drags. */
    private var powerOffset = 0f

    /** Power drag in progress — snapshot sync must not overwrite the slider. */
    val draggingPower: Boolean get() = powerPointer != INACTIVE
    val draggingSpin: Boolean get() = spinPointer != INACTIVE
    val draggingAim: Boolean get() = aimPointer != INACTIVE

    fun isPressed(press: HudPress): Boolean = presses.containsValue(press)

    fun onDown(pointerId: Int, x: Float, y: Float): List<HudEffect> {
        val l = layout()
        val c = context()
        return when (classify(l, c, x, y)) {
            Zone.PAUSE -> {
                presses[pointerId] = HudPress.PAUSE
                emptyList()
            }
            Zone.FAST_FORWARD -> {
                presses[pointerId] = HudPress.FAST_FORWARD
                emptyList()
            }
            Zone.SHOOT -> {
                // One armed shoot press at a time; extra fingers are ignored.
                if (presses.containsValue(HudPress.SHOOT)) emptyList()
                else {
                    presses[pointerId] = HudPress.SHOOT
                    listOf(HudEffect.UiTap)
                }
            }
            Zone.POWER -> {
                if (powerPointer != INACTIVE) emptyList()
                else {
                    powerPointer = pointerId
                    // Relative tracking: remember where the grab happened
                    // relative to the current power so the slider does not jump.
                    powerOffset = powerAnchor() - yToPower(l, y)
                    emptyList()
                }
            }
            Zone.SPIN -> {
                if (spinPointer != INACTIVE) emptyList()
                else {
                    spinPointer = pointerId
                    listOf(spin(l, x, y))
                }
            }
            Zone.TABLE -> {
                if (aimPointer != INACTIVE) emptyList()
                else {
                    aimPointer = pointerId
                    aim(c, x, y)
                }
            }
            null -> emptyList()
        }
    }

    fun onMove(pointerId: Int, x: Float, y: Float): List<HudEffect> {
        val l = layout()
        return when (pointerId) {
            powerPointer -> {
                val v = (yToPower(l, y) + powerOffset).coerceIn(POWER_MIN, 1f)
                listOf(HudEffect.PowerChanged(v.toDouble()))
            }
            spinPointer -> listOf(spin(l, x, y))
            aimPointer -> aim(context(), x, y)
            else -> emptyList()
        }.also {
            // Sliding off the shoot button before release disarms the strike.
            if (presses[pointerId] == HudPress.SHOOT && !l.containsShoot(x, y)) {
                presses.remove(pointerId)
            }
        }
    }

    fun onUp(pointerId: Int, x: Float, y: Float): List<HudEffect> {
        val c = context()
        val out = ArrayList<HudEffect>()
        when (presses.remove(pointerId)) {
            HudPress.PAUSE -> out.add(HudEffect.Pause)
            HudPress.FAST_FORWARD -> out.add(HudEffect.FastForward)
            HudPress.SHOOT -> if (c.canShoot) out.add(HudEffect.Shoot)
            null -> Unit
        }
        when (pointerId) {
            aimPointer -> {
                aimPointer = INACTIVE
                // Placement is decided by the state at release, not at press.
                if (c.state == SessionState.BALL_IN_HAND) {
                    aimMapper(x, y)?.let { out.add(HudEffect.PlaceCue(it)) }
                }
            }
            powerPointer -> powerPointer = INACTIVE
            spinPointer -> spinPointer = INACTIVE
        }
        return out
    }

    /** Gesture aborted by the framework (e.g. parent intercepted): reset all. */
    fun onCancel() {
        aimPointer = INACTIVE
        powerPointer = INACTIVE
        spinPointer = INACTIVE
        presses.clear()
    }

    private fun aim(c: HudTouchContext, x: Float, y: Float): List<HudEffect> {
        // During ball-in-hand a table drag only previews placement; the actual
        // move is committed by onUp. Matches the original aimAt() gating.
        if (c.state == SessionState.BALL_IN_HAND) return emptyList()
        val p = aimMapper(x, y) ?: return emptyList()
        return listOf(HudEffect.AimAt(p))
    }

    private fun spin(l: HudLayout, x: Float, y: Float): HudEffect {
        var dx = (x - l.spinCenterX) / (l.spinRadius * 0.72f)
        var dy = -(y - l.spinCenterY) / (l.spinRadius * 0.72f)
        val len = hypot(dx, dy)
        if (len > 1f) {
            dx /= len
            dy /= len
        }
        // +0f normalizes -0f so the engine never sees a negative-zero spin.
        return HudEffect.SpinChanged(
            (dx.coerceIn(-1f, 1f) + 0f).toDouble() * 0.5,
            (dy.coerceIn(-1f, 1f) + 0f).toDouble() * 0.5,
        )
    }

    private fun yToPower(l: HudLayout, y: Float): Float = (l.powerRect.bottom - y) / l.powerRect.height

    private enum class Zone { PAUSE, FAST_FORWARD, SHOOT, POWER, SPIN, TABLE }

    private fun classify(l: HudLayout, c: HudTouchContext, x: Float, y: Float): Zone? = when {
        l.pauseRect.contains(x, y) -> Zone.PAUSE
        c.state == SessionState.SIMULATING && l.ffRect.contains(x, y) -> Zone.FAST_FORWARD
        c.canShoot && l.containsShoot(x, y) -> Zone.SHOOT
        l.powerTouch.contains(x, y) -> Zone.POWER
        l.containsSpin(x, y) -> Zone.SPIN
        y > l.aimTopGuard && (c.state == SessionState.AIMING || c.state == SessionState.BALL_IN_HAND) ->
            Zone.TABLE
        else -> null
    }

    private companion object {
        const val INACTIVE = -1
        const val POWER_MIN = 0.05f
    }
}

package com.minamikune.billiards.ui

import kotlin.math.hypot
import kotlin.math.min

/**
 * Android-free float rectangle. Mirrors the subset of android.graphics.RectF
 * the HUD needs so layout math stays unit-testable on the JVM.
 */
data class FRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun contains(x: Float, y: Float): Boolean = x >= left && x <= right && y >= top && y <= bottom

    /** Positive values shrink, negative values expand — same contract as RectF.inset. */
    fun inset(dx: Float, dy: Float): FRect = FRect(left + dx, top + dy, right - dx, bottom - dy)
}

/**
 * Geometry of every interactive HUD control, as a pure function of view size,
 * safe insets and display density. Extracted verbatim from
 * `GameHudView.layoutControls` so layout invariants (controls clear of the
 * safe area, hit slop, minimum touch sizes) can be unit tested.
 */
class HudLayout(
    val powerRect: FRect,
    /** Expanded grab area around [powerRect]. */
    val powerTouch: FRect,
    val pauseRect: FRect,
    val shootCenterX: Float,
    val shootCenterY: Float,
    val shootRadius: Float,
    val spinCenterX: Float,
    val spinCenterY: Float,
    val spinRadius: Float,
    val ffRect: FRect,
    /** Table drags starting above this Y are ignored (top scoreboard guard). */
    val aimTopGuard: Float,
) {
    /** Same slop factor the shoot button used in its original hit test. */
    fun containsShoot(x: Float, y: Float): Boolean =
        hypot(x - shootCenterX, y - shootCenterY) <= shootRadius * 1.15f

    fun containsSpin(x: Float, y: Float): Boolean =
        hypot(x - spinCenterX, y - spinCenterY) <= spinRadius * 1.12f
}

/** Exact port of GameHudView.layoutControls constants and formulas. */
fun computeHudLayout(
    w: Int,
    h: Int,
    safeL: Int,
    safeT: Int,
    safeR: Int,
    safeB: Int,
    density: Float,
): HudLayout {
    fun dp(v: Float): Float = v * density
    val margin = dp(18f)
    val ph = min(dp(228f), h * 0.42f).coerceAtLeast(dp(150f))
    val pw = dp(30f)
    val powerRect = FRect(
        w - safeR - margin - pw,
        h * 0.46f - ph / 2f,
        w - safeR - margin,
        h * 0.46f + ph / 2f,
    )
    val shootRadius = min(dp(52f), min(w, h) * 0.085f).coerceAtLeast(dp(38f))
    val spinRadius = min(dp(48f), min(w, h) * 0.082f).coerceAtLeast(dp(38f))
    return HudLayout(
        powerRect = powerRect,
        powerTouch = powerRect.inset(-dp(20f), -dp(8f)),
        pauseRect = FRect(
            w - safeR - margin - dp(42f),
            safeT + dp(14f),
            w - safeR - margin,
            safeT + dp(56f),
        ),
        shootCenterX = w - safeR - margin - shootRadius,
        shootCenterY = h - safeB - margin - shootRadius,
        shootRadius = shootRadius,
        spinCenterX = safeL + margin + spinRadius,
        spinCenterY = h - safeB - margin - spinRadius,
        spinRadius = spinRadius,
        ffRect = FRect(
            w / 2f - dp(72f),
            h - safeB - dp(52f),
            w / 2f + dp(72f),
            h - safeB - dp(18f),
        ),
        aimTopGuard = safeT + dp(112f),
    )
}

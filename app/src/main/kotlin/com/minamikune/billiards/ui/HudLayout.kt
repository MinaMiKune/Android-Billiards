package com.minamikune.billiards.ui

import com.minamikune.billiards.platform.FRect
import com.minamikune.billiards.platform.FoldLayoutGuide
import com.minamikune.billiards.platform.FoldPosture
import kotlin.math.hypot
import kotlin.math.min

/**
 * Geometry of every interactive HUD control, as a pure function of view size,
 * safe insets, display density and (optionally) the foldable
 * [FoldLayoutGuide]. Extracted verbatim from `GameHudView.layoutControls` so
 * layout invariants (controls clear of the safe area, hit slop, minimum touch
 * sizes) can be unit tested.
 *
 * Stage 6: a null guide (or a FLAT posture) reproduces the exact stage 5
 * baseline formulas; TABLETOP and BOOK re-arrange controls around the fold.
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

/**
 * Stage 5 baseline control geometry, byte-for-byte the same computation the
 * HUD has always used. Kept as a separate function so the fold-aware
 * [computeHudLayout] can fall back to it and the equivalence is obvious.
 */
private fun baselineHudLayout(
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

/**
 * Exact port of GameHudView.layoutControls constants and formulas, extended
 * with an optional foldable layout guide.
 *
 * @param fold null (default), FLAT or no-hinge guides all take the baseline
 *   branch and reproduce the stage 5 geometry exactly.
 */
fun computeHudLayout(
    w: Int,
    h: Int,
    safeL: Int,
    safeT: Int,
    safeR: Int,
    safeB: Int,
    density: Float,
    fold: FoldLayoutGuide? = null,
): HudLayout {
    val guide = fold?.takeUnless { it.posture == FoldPosture.FLAT }
        ?: return baselineHudLayout(w, h, safeL, safeT, safeR, safeB, density)
    return when (guide.posture) {
        FoldPosture.TABLETOP -> tabletopHudLayout(w, h, safeT, safeR, density, guide)
        FoldPosture.BOOK -> bookHudLayout(w, h, safeL, safeT, safeR, safeB, density, guide)
        FoldPosture.FLAT -> error("FLAT guides are handled by the baseline branch")
    }
}

/**
 * Laptop-like tabletop posture: spin bottom-left, shoot bottom-right,
 * fast-forward bottom-center inside [FoldLayoutGuide.controlZone] (the lower
 * half), and the power bar anchored to the zone's top-right corner so it
 * clears the crease by construction. The pause button and aim guard stay
 * window-relative so the top scoreboard keeps its stage 5 position.
 */
private fun tabletopHudLayout(
    w: Int,
    h: Int,
    safeT: Int,
    safeR: Int,
    density: Float,
    guide: FoldLayoutGuide,
): HudLayout {
    fun dp(v: Float): Float = v * density
    val margin = dp(18f)
    val zone = guide.controlZone
    val zw = zone.width
    val zh = zone.height
    val shootRadius = min(dp(52f), min(zw, zh) * 0.085f).coerceAtLeast(dp(38f))
    val spinRadius = min(dp(48f), min(zw, zh) * 0.082f).coerceAtLeast(dp(38f))
    val shootCenterX = zone.right - margin - shootRadius
    val shootCenterY = zone.bottom - margin - shootRadius
    val spinCenterX = zone.left + margin + spinRadius
    val spinCenterY = zone.bottom - margin - spinRadius
    val ffRect = FRect(
        zone.centerX - dp(72f),
        zone.bottom - dp(52f),
        zone.centerX + dp(72f),
        zone.bottom - dp(18f),
    )
    val ph = min(dp(228f), zh * 0.55f).coerceAtLeast(dp(140f))
    val powerRect = FRect(
        zone.right - margin - dp(30f),
        zone.top + dp(12f),
        zone.right - margin,
        zone.top + dp(12f) + ph,
    )
    return HudLayout(
        powerRect = powerRect,
        powerTouch = powerRect.inset(-dp(20f), -dp(8f)),
        pauseRect = FRect(
            w - safeR - margin - dp(42f),
            safeT + dp(14f),
            w - safeR - margin,
            safeT + dp(56f),
        ),
        shootCenterX = shootCenterX,
        shootCenterY = shootCenterY,
        shootRadius = shootRadius,
        spinCenterX = spinCenterX,
        spinCenterY = spinCenterY,
        spinRadius = spinRadius,
        ffRect = ffRect,
        aimTopGuard = safeT + dp(112f),
    )
}

/**
 * Book-like posture (vertical crease, half-opened): baseline geometry, then
 * every control is pushed clear of the crease band to the wider side of the
 * window, keeping its size and staying fully on-screen.
 */
private fun bookHudLayout(
    w: Int,
    h: Int,
    safeL: Int,
    safeT: Int,
    safeR: Int,
    safeB: Int,
    density: Float,
    guide: FoldLayoutGuide,
): HudLayout {
    fun dp(v: Float): Float = v * density
    val margin = dp(18f)
    val band = guide.avoidZones.first()

    /** Shift [rect] fully to one side of the crease band, keeping its size. */
    fun clear(rect: FRect): FRect {
        if (rect.right <= band.left || rect.left >= band.right) return rect
        val rw = rect.width
        return if (band.left >= w - band.right) {
            val right = band.left.coerceAtLeast(rw)
            FRect(right - rw, rect.top, right, rect.bottom)
        } else {
            val left = band.right.coerceAtMost(w - rw)
            FRect(left, rect.top, left + rw, rect.bottom)
        }
    }

    /** Push a circular control (bounding box) clear of the band. */
    fun clearCircle(cx: Float, cy: Float, r: Float): Pair<Float, Float> {
        val c = clear(FRect(cx - r, cy - r, cx + r, cy + r))
        return c.centerX to c.centerY
    }

    val base = baselineHudLayout(w, h, safeL, safeT, safeR, safeB, density)
    val powerRect = clear(base.powerRect)
    val (shootX, shootY) = clearCircle(base.shootCenterX, base.shootCenterY, base.shootRadius)
    val (spinX, spinY) = clearCircle(base.spinCenterX, base.spinCenterY, base.spinRadius)
    return HudLayout(
        powerRect = powerRect,
        powerTouch = powerRect.inset(-dp(20f), -dp(8f)),
        pauseRect = clear(base.pauseRect),
        shootCenterX = shootX,
        shootCenterY = shootY,
        shootRadius = base.shootRadius,
        spinCenterX = spinX,
        spinCenterY = spinY,
        spinRadius = base.spinRadius,
        ffRect = clear(base.ffRect),
        aimTopGuard = base.aimTopGuard,
    )
}

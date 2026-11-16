package com.minamikune.billiards.platform

/**
 * Pure foldable-posture model + layout math for stage 6 (Android 17 /
 * foldables). Everything here is Android-free so the geometry is unit-testable
 * on the JVM; [FoldPostureTracker] bridges androidx.window events into this
 * model, and HUD / menus / renderer consume [FoldLayoutGuide] directly.
 *
 * Postures:
 *  - FLAT: no fold feature, or the device is fully opened/closed. Consumers
 *    keep the baseline phone layout; the guide is a full-window pass-through.
 *  - TABLETOP (HALF_OPENED + horizontal hinge): laptop-like. The upper half of
 *    the window is the table viewport (camera frames the table there), the
 *    lower half hosts HUD controls, and the crease band is keep-clear.
 *  - BOOK (HALF_OPENED + vertical hinge): book-like. Camera and controls stay
 *    full-window but every control is pushed clear of the vertical crease band
 *    to the wider side.
 */

enum class FoldOrientation { HORIZONTAL, VERTICAL }

enum class FoldPosture { FLAT, TABLETOP, BOOK }

/**
 * A fold hinge translated from androidx.window's FoldingFeature into
 * density-independent, window-local px so the math below needs no Android.
 *
 * @param center hinge center in window px: Y coordinate for a HORIZONTAL
 *   hinge, X coordinate for a VERTICAL one.
 * @param thickness hinge thickness in px (FoldingFeature bounds' short edge).
 */
class FoldHinge(
    val orientation: FoldOrientation,
    val center: Float,
    val thickness: Float,
    val halfOpened: Boolean,
)

/**
 * Derived layout regions for the current posture. All rects are window-local
 * px, same coordinate space as the content view (y grows downward).
 */
class FoldLayoutGuide(
    val posture: FoldPosture,
    /** Region the camera frames the table into; full window unless TABLETOP. */
    val tableViewport: FRect,
    /** Region HUD controls live in; full window unless TABLETOP. */
    val controlZone: FRect,
    /** Keep-clear bands (the crease) that controls must not overlap. */
    val avoidZones: List<FRect>,
) {
    companion object {
        /** Pass-through guide used whenever there is no usable fold state. */
        fun flat(w: Float, h: Float) = FoldLayoutGuide(
            FoldPosture.FLAT,
            FRect(0f, 0f, w, h),
            FRect(0f, 0f, w, h),
            emptyList(),
        )
    }
}

/** A split is only adopted when both sides stay at least this tall/wide. */
private const val MIN_USABLE_FRACTION = 0.35f

/**
 * Maps a hinge (or null) to a [FoldLayoutGuide].
 *
 * @param marginPx keep-clear margin around the crease, applied on both sides.
 */
fun computeFoldLayout(
    windowW: Float,
    windowH: Float,
    hinge: FoldHinge?,
    marginPx: Float,
): FoldLayoutGuide {
    if (windowW <= 0f || windowH <= 0f || hinge == null || !hinge.halfOpened) {
        return FoldLayoutGuide.flat(windowW, windowH)
    }
    val m = marginPx.coerceAtLeast(0f)
    return when (hinge.orientation) {
        FoldOrientation.HORIZONTAL -> {
            val hingeTop = hinge.center - hinge.thickness / 2f
            val hingeBottom = hinge.center + hinge.thickness / 2f
            val topH = hingeTop - m
            val bottomH = windowH - hingeBottom - m
            // Fold pressed near the top/bottom edge: the split would leave a
            // sliver of unusable space, so keep the full-window baseline.
            if (topH < windowH * MIN_USABLE_FRACTION || bottomH < windowH * MIN_USABLE_FRACTION) {
                FoldLayoutGuide.flat(windowW, windowH)
            } else {
                FoldLayoutGuide(
                    posture = FoldPosture.TABLETOP,
                    tableViewport = FRect(0f, 0f, windowW, hingeTop - m),
                    controlZone = FRect(0f, hingeBottom + m, windowW, windowH),
                    avoidZones = listOf(FRect(0f, hingeTop, windowW, hingeBottom)),
                )
            }
        }
        FoldOrientation.VERTICAL -> {
            val hingeLeft = hinge.center - hinge.thickness / 2f
            val hingeRight = hinge.center + hinge.thickness / 2f
            val leftW = hingeLeft - m
            val rightW = windowW - hingeRight - m
            if (leftW < windowW * MIN_USABLE_FRACTION || rightW < windowW * MIN_USABLE_FRACTION) {
                FoldLayoutGuide.flat(windowW, windowH)
            } else {
                FoldLayoutGuide(
                    posture = FoldPosture.BOOK,
                    tableViewport = FRect(0f, 0f, windowW, windowH),
                    controlZone = FRect(0f, 0f, windowW, windowH),
                    avoidZones = listOf(FRect(hingeLeft - m, 0f, hingeRight + m, windowH)),
                )
            }
        }
    }
}

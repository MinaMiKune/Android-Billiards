package com.minamikune.billiards.platform

/**
 * Merges system-bar insets with display-cutout safe insets.
 *
 * 2026-11-11 real-device finding: on notch devices the HUD top bar was drawn
 * under the cutout. HUD/Menu already offset by `safeT` etc., but on the
 * API 28–29 path only `systemWindowInset*` was read; in immersive mode the
 * hidden system bars report zero insets, so the notch area was never
 * accounted for. Merging `DisplayCutout.safeInset*` (API 28+) into both SDK
 * branches keeps the top scoreboard, pause button and menus clear of the
 * notch even while the system bars stay hidden.
 *
 * Pure JVM Kotlin so the merge rule is unit-testable without Android.
 */
object SafeInsets {

    /** Simple left/top/right/bottom inset tuple (avoids android.graphics.Insets). */
    data class LTRB(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /**
     * Component-wise maximum of the system-bar insets and the cutout safe
     * insets. A null cutout means the device/window has no reported cutout,
     * in which case the system-bar insets pass through unchanged.
     */
    fun merge(systemBars: LTRB, cutout: LTRB?): LTRB {
        val c = cutout ?: return systemBars
        return LTRB(
            left = maxOf(systemBars.left, c.left),
            top = maxOf(systemBars.top, c.top),
            right = maxOf(systemBars.right, c.right),
            bottom = maxOf(systemBars.bottom, c.bottom),
        )
    }
}

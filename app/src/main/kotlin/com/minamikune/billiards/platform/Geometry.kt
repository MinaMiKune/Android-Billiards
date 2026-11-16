package com.minamikune.billiards.platform

/**
 * Android-free float rectangle shared by HUD/menu layout and the foldable
 * layout guides.
 *
 * 2026-11-16 (stage 6): moved here from `ui.HudLayout` so platform-level
 * models (FoldLayoutGuide) can use it without a platform -> ui package
 * dependency. `ui` now imports this type like any other consumer; no other
 * source change was needed because nothing referenced the old class by name
 * except HudLayout itself and tests that only go through computeHudLayout.
 */
data class FRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {

    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun contains(x: Float, y: Float): Boolean = x >= left && x <= right && y >= top && y <= bottom

    fun inset(dx: Float, dy: Float): FRect = FRect(left + dx, top + dy, right - dx, bottom - dy)
}

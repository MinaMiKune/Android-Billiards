package com.minamikune.billiards.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Layout invariants for the in-game HUD: controls stay clear of the safe
 * area, grab slop expands the power bar, and hit tests use the documented
 * slop factors.
 */
class HudLayoutTest {
    private val layout = computeHudLayout(
        w = 1080,
        h = 2400,
        safeL = 24,
        safeT = 80,
        safeR = 24,
        safeB = 120,
        density = 2.625f,
    )

    @Test
    fun powerBarSitsRightOfSafeAreaAndInsideScreen() {
        assertTrue(layout.powerRect.left >= 24f)
        assertTrue(layout.powerRect.right <= 1080f - 24f)
        assertTrue(layout.powerRect.width > 0f)
        assertTrue(layout.powerRect.top >= 0f)
        assertTrue(layout.powerRect.bottom <= 2400f)
    }

    @Test
    fun powerTouchIsExpandedBeyondPowerBar() {
        assertTrue(layout.powerTouch.left < layout.powerRect.left)
        assertTrue(layout.powerTouch.right > layout.powerRect.right)
        assertTrue(layout.powerTouch.top < layout.powerRect.top)
        assertTrue(layout.powerTouch.bottom > layout.powerRect.bottom)
    }

    @Test
    fun pauseButtonClearsTopSafeInset() {
        assertTrue(layout.pauseRect.top >= 80f)
        assertTrue(layout.pauseRect.bottom > layout.pauseRect.top)
        assertTrue(layout.pauseRect.right <= 1080f - 24f)
    }

    @Test
    fun bottomControlsSitAboveBottomSafeInset() {
        assertTrue(layout.shootCenterY + layout.shootRadius <= 2400f - 120f)
        assertTrue(layout.spinCenterY + layout.spinRadius <= 2400f - 120f)
    }

    @Test
    fun spinAndShootCirclesDoNotOverlapHorizontally() {
        val spinRight = layout.spinCenterX + layout.spinRadius
        val shootLeft = layout.shootCenterX - layout.shootRadius
        assertTrue("spin disc must stay left of the shoot button", spinRight < shootLeft)
    }

    @Test
    fun radiiRespectMinimumTouchSize() {
        // dp(38) ≈ 99.75px at this density: a circle below ~48dp is too small
        // to aim reliably, so the layout must clamp the radii upwards.
        assertTrue(layout.shootRadius >= 38f * 2.625f)
        assertTrue(layout.spinRadius >= 38f * 2.625f)
    }

    @Test
    fun shootHitTestUsesSlopFactor() {
        val r = layout.shootRadius
        // A point 1.10x the radius away must still register as a tap...
        val angle = 0.7f
        val x = layout.shootCenterX + r * 1.10f * kotlin.math.cos(angle)
        val y = layout.shootCenterY + r * 1.10f * kotlin.math.sin(angle)
        assertTrue(layout.containsShoot(x, y))
        // ...while a point at 1.20x the radius must not.
        val x2 = layout.shootCenterX + r * 1.20f * kotlin.math.cos(angle)
        val y2 = layout.shootCenterY + r * 1.20f * kotlin.math.sin(angle)
        assertFalse(layout.containsShoot(x2, y2))
    }

    @Test
    fun aimTopGuardFollowsSafeInset() {
        assertEquals(80f + 112f * 2.625f, layout.aimTopGuard, 0.001f)
    }

    @Test
    fun layoutFitsTallNarrowScreen() {
        val tall = computeHudLayout(
            w = 720,
            h = 3200,
            safeL = 0,
            safeT = 60,
            safeR = 0,
            safeB = 90,
            density = 2.0f,
        )
        assertTrue(tall.powerRect.bottom <= 3200f)
        assertTrue(tall.ffRect.bottom <= 3200f - 90f)
        assertTrue(tall.powerRect.height >= 150f * 2.0f)
    }

    @Test
    fun layoutFitsShortLandscapePhone() {
        val short = computeHudLayout(
            w = 2400,
            h = 1080,
            safeL = 0,
            safeT = 40,
            safeR = 0,
            safeB = 40,
            density = 2.0f,
        )
        assertTrue(short.shootCenterY - short.shootRadius >= 0f)
        assertTrue(short.powerRect.top >= 0f)
        assertTrue(short.powerRect.bottom <= 1080f)
    }
}

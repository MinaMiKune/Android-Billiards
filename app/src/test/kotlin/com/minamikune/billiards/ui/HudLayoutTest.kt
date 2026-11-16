package com.minamikune.billiards.ui

import com.minamikune.billiards.platform.FoldHinge
import com.minamikune.billiards.platform.FoldLayoutGuide
import com.minamikune.billiards.platform.FoldOrientation
import com.minamikune.billiards.platform.computeFoldLayout
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

    // ------------------------------------------------------ foldable postures

    @Test
    fun flatGuideReproducesBaselineLayoutExactly() {
        val flat = FoldLayoutGuide.flat(1080f, 2400f)
        val withNull = computeHudLayout(1080, 2400, 24, 80, 24, 120, 2.625f, null)
        val withFlat = computeHudLayout(1080, 2400, 24, 80, 24, 120, 2.625f, flat)
        assertEquals(withNull.powerRect, withFlat.powerRect)
        assertEquals(withNull.powerTouch, withFlat.powerTouch)
        assertEquals(withNull.pauseRect, withFlat.pauseRect)
        assertEquals(withNull.ffRect, withFlat.ffRect)
        assertEquals(withNull.shootCenterX, withFlat.shootCenterX, 0.001f)
        assertEquals(withNull.shootCenterY, withFlat.shootCenterY, 0.001f)
        assertEquals(withNull.shootRadius, withFlat.shootRadius, 0.001f)
        assertEquals(withNull.spinCenterX, withFlat.spinCenterX, 0.001f)
        assertEquals(withNull.spinCenterY, withFlat.spinCenterY, 0.001f)
        assertEquals(withNull.spinRadius, withFlat.spinRadius, 0.001f)
        assertEquals(withNull.aimTopGuard, withFlat.aimTopGuard, 0.001f)
    }

    private fun tabletopGuide(): FoldLayoutGuide {
        // 1600x1600 inner display, horizontal hinge at the middle.
        val hinge = FoldHinge(FoldOrientation.HORIZONTAL, 800f, 16f, halfOpened = true)
        return computeFoldLayout(1600f, 1600f, hinge, marginPx = 40f)
    }

    @Test
    fun tabletopKeepsEveryControlInsideTheControlZone() {
        val guide = tabletopGuide()
        val l = computeHudLayout(1600, 1600, 32, 60, 32, 48, 2.0f, guide)
        val zone = guide.controlZone
        // Spin bottom-left, shoot bottom-right, ff bottom-center: all inside.
        assertTrue(l.spinCenterX - l.spinRadius >= zone.left)
        assertTrue(l.shootCenterX + l.shootRadius <= zone.right)
        assertTrue(l.ffRect.left >= zone.left && l.ffRect.right <= zone.right)
        assertTrue(l.ffRect.bottom <= zone.bottom)
        // Power bar is anchored to the zone's top-right, above the ff row.
        assertTrue(l.powerRect.top >= zone.top)
        assertTrue(l.powerRect.right <= zone.right)
        assertTrue(l.powerRect.bottom <= l.ffRect.top)
    }

    @Test
    fun tabletopControlsClearTheCrease() {
        val guide = tabletopGuide()
        val l = computeHudLayout(1600, 1600, 32, 60, 32, 48, 2.0f, guide)
        val crease = guide.avoidZones.first()
        // Zone controls sit below the crease; the window-level pause button
        // sits above it. Nothing may straddle the band.
        for (r in listOf(l.powerRect, l.ffRect, l.pauseRect)) {
            assertTrue("rect $r must not touch the crease $crease", r.bottom <= crease.top || r.top >= crease.bottom)
        }
        assertTrue(l.spinCenterY - l.spinRadius >= crease.bottom)
        assertTrue(l.shootCenterY - l.shootRadius >= crease.bottom)
    }

    @Test
    fun tabletopRadiiRespectMinimumTouchSize() {
        val l = computeHudLayout(1600, 1600, 32, 60, 32, 48, 2.0f, tabletopGuide())
        assertTrue(l.shootRadius >= 38f * 2.0f)
        assertTrue(l.spinRadius >= 38f * 2.0f)
    }

    private fun bookGuide(): FoldLayoutGuide {
        val hinge = FoldHinge(FoldOrientation.VERTICAL, 1100f, 16f, halfOpened = true)
        return computeFoldLayout(2200f, 1600f, hinge, marginPx = 40f)
    }

    @Test
    fun bookPushesControlsClearOfTheCrease() {
        val guide = bookGuide()
        val l = computeHudLayout(2200, 1600, 0, 60, 0, 48, 2.0f, guide)
        val band = guide.avoidZones.first()
        for (r in listOf(l.powerRect, l.ffRect, l.pauseRect)) {
            assertTrue("rect $r overlaps the crease band $band", r.right <= band.left || r.left >= band.right)
        }
        for (c in listOf(
            Triple(l.shootCenterX, l.shootCenterY, l.shootRadius),
            Triple(l.spinCenterX, l.spinCenterY, l.spinRadius),
        )) {
            val (cx, cy, rad) = c
            assertTrue(
                "circle at $cx,$cy r=$rad overlaps the crease band $band",
                cx + rad <= band.left || cx - rad >= band.right || cy + rad <= band.top || cy - rad >= band.bottom,
            )
        }
    }

    @Test
    fun bookKeepsControlsOnScreen() {
        val l = computeHudLayout(2200, 1600, 0, 60, 0, 48, 2.0f, bookGuide())
        assertTrue(l.shootCenterX - l.shootRadius >= 0f && l.shootCenterX + l.shootRadius <= 2200f)
        assertTrue(l.spinCenterX - l.spinRadius >= 0f && l.spinCenterX + l.spinRadius <= 2200f)
        assertTrue(l.ffRect.left >= 0f && l.ffRect.right <= 2200f)
        assertTrue(l.powerRect.top >= 0f && l.powerRect.bottom <= 1600f)
    }

    /**
     * 600×900 freeform window (roadmap §7.2): the tightest class the game must
     * support. Controls may shrink toward the 38dp floor but never leave the
     * safe area or overlap each other.
     */
    @Test
    fun smallWindow600x900KeepsControlsUsableAndInsideSafeArea() {
        val l = computeHudLayout(600, 900, 24, 60, 24, 96, 2.0f)
        assertTrue(l.powerRect.left >= 24f)
        assertTrue(l.powerRect.right <= 600f - 24f)
        assertTrue(l.pauseRect.top >= 60f)
        assertTrue(l.pauseRect.right <= 600f - 24f)
        assertTrue(l.shootCenterY + l.shootRadius <= 900f - 96f)
        assertTrue(l.spinCenterY + l.spinRadius <= 900f - 96f)
        assertTrue(l.shootRadius >= 38f * 2.0f)
        assertTrue(l.spinRadius >= 38f * 2.0f)
        assertTrue(l.spinCenterX + l.spinRadius < l.shootCenterX - l.shootRadius)
    }
}

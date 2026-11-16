package com.minamikune.billiards.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Foldable layout math (stage 6). All inputs are window-local px, so the
 * geometry is verified directly without Android or Robolectric.
 */
class FoldLayoutTest {

    private fun horizontalHinge(center: Float, thickness: Float = 16f) =
        FoldHinge(FoldOrientation.HORIZONTAL, center, thickness, halfOpened = true)

    private fun verticalHinge(center: Float, thickness: Float = 16f) =
        FoldHinge(FoldOrientation.VERTICAL, center, thickness, halfOpened = true)

    // ------------------------------------------------------------ baseline

    @Test
    fun noHingeYieldsFlatFullWindowGuide() {
        val g = computeFoldLayout(1600f, 2200f, null, marginPx = 40f)
        assertEquals(FoldPosture.FLAT, g.posture)
        assertEquals(FRect(0f, 0f, 1600f, 2200f), g.tableViewport)
        assertEquals(FRect(0f, 0f, 1600f, 2200f), g.controlZone)
        assertTrue(g.avoidZones.isEmpty())
    }

    @Test
    fun flatHingeYieldsFlatGuide() {
        val flat = FoldHinge(FoldOrientation.HORIZONTAL, 1100f, 16f, halfOpened = false)
        val g = computeFoldLayout(1600f, 2200f, flat, marginPx = 40f)
        assertEquals(FoldPosture.FLAT, g.posture)
        assertTrue(g.avoidZones.isEmpty())
    }

    @Test
    fun degenerateWindowYieldsFlatGuide() {
        val g = computeFoldLayout(0f, 2200f, horizontalHinge(1100f), marginPx = 40f)
        assertEquals(FoldPosture.FLAT, g.posture)
    }

    // ------------------------------------------------------------ tabletop

    @Test
    fun horizontalHalfOpenedYieldsTabletopSplit() {
        // 2200 px tall inner display, hinge across the middle (Flip-style).
        val g = computeFoldLayout(1600f, 2200f, horizontalHinge(1100f), marginPx = 40f)
        assertEquals(FoldPosture.TABLETOP, g.posture)
        // Viewport = above the hinge band minus margin: 0..(1092-40).
        assertEquals(FRect(0f, 0f, 1600f, 1052f), g.tableViewport)
        // Controls = below the hinge band plus margin: (1108+40)..2200.
        assertEquals(FRect(0f, 1148f, 1600f, 2200f), g.controlZone)
        // Crease band itself is keep-clear.
        assertEquals(listOf(FRect(0f, 1092f, 1600f, 1108f)), g.avoidZones)
    }

    @Test
    fun tabletopHalvesBothStayUsable() {
        val g = computeFoldLayout(1812f, 2176f, horizontalHinge(1088f), marginPx = 42f)
        assertEquals(FoldPosture.TABLETOP, g.posture)
        val top = g.tableViewport.height
        val bottom = g.controlZone.height
        assertTrue(top >= 2176f * 0.35f)
        assertTrue(bottom >= 2176f * 0.35f)
        assertTrue(g.controlZone.top > g.tableViewport.bottom)
    }

    @Test
    fun hingeNearEdgeFallsBackToFlat() {
        // Hinge 100 px from the top with a 40 px margin: upper half would be a
        // 60 px sliver, far below the usable fraction — keep the full window.
        val g = computeFoldLayout(1600f, 2200f, horizontalHinge(100f), marginPx = 40f)
        assertEquals(FoldPosture.FLAT, g.posture)
    }

    // ------------------------------------------------------------ book

    @Test
    fun verticalHalfOpenedYieldsBookBand() {
        // Book-style vertical crease down the middle.
        val g = computeFoldLayout(2200f, 1600f, verticalHinge(1100f), marginPx = 40f)
        assertEquals(FoldPosture.BOOK, g.posture)
        // Full window still usable for camera and controls...
        assertEquals(FRect(0f, 0f, 2200f, 1600f), g.tableViewport)
        assertEquals(FRect(0f, 0f, 2200f, 1600f), g.controlZone)
        // ...but the crease band is keep-clear (hinge 1092..1108 plus margin).
        assertEquals(listOf(FRect(1052f, 0f, 1148f, 1600f)), g.avoidZones)
    }

    @Test
    fun verticalHingeNearEdgeFallsBackToFlat() {
        val g = computeFoldLayout(2200f, 1600f, verticalHinge(120f), marginPx = 40f)
        assertEquals(FoldPosture.FLAT, g.posture)
    }
}

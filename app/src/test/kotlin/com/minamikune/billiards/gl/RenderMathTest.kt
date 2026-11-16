package com.minamikune.billiards.gl

import com.minamikune.billiards.physics.TableSpec
import com.minamikune.billiards.physics.Vec2
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies camera fitting, aim targeting and the screen<->table mappings. */
class RenderMathTest {

    private data class Ball(
        override var x: Double,
        override var z: Double,
        override var active: Boolean = true,
    ) : RenderMath.BallXY

    private fun balls(vararg xy: Pair<Double, Double>, inactive: Set<Int> = emptySet()): List<Ball> =
        listOf(Ball(0.0, 0.0)) + xy.mapIndexed { i, (x, z) -> Ball(x, z, active = (i + 1) !in inactive) }

    private val r = TableSpec.BALL_RADIUS
    private val maxX = TableSpec.HALF_LENGTH - r
    private val maxZ = TableSpec.HALF_WIDTH - r

    // ---- camera fitting -------------------------------------------------

    private fun allCornersInside(proj: FloatArray, portrait: Boolean, distance: Double): Boolean {
        val dir = com.minamikune.billiards.physics.Vec3(0.0, 0.86, 0.51).normalized()
        val eye = com.minamikune.billiards.physics.Vec3(dir.x * distance, dir.y * distance, dir.z * distance)
        val view = Mat4.lookAt(eye, com.minamikune.billiards.physics.Vec3(0.0, 0.0, 0.0), com.minamikune.billiards.physics.Vec3(0.0, 1.0, 0.0))
        val vp = Mat4.multiply(proj, view)
        val hx = if (portrait) TableSpec.HALF_WIDTH + 0.20 else TableSpec.HALF_LENGTH + 0.20
        val hz = if (portrait) TableSpec.HALF_LENGTH + 0.20 else TableSpec.HALF_WIDTH + 0.20
        for (sx in listOf(-1f, 1f)) for (sz in listOf(-1f, 1f)) for (y in listOf(0f, 0.12f)) {
            val p = Mat4.transformPoint(vp, sx * hx.toFloat(), y, sz * hz.toFloat())
            if (abs(p.x) > 0.90 || abs(p.y) > 0.80) return false
        }
        return true
    }

    @Test
    fun fitCameraKeepsWholeTableInSafeZoneAcrossAspectsAndOrientations() {
        for (aspect in floatArrayOf(2.39f, 2.0f, 1.78f, 1.33f, 1.0f, 0.75f, 0.5625f, 0.46f)) {
            val proj = Mat4.perspective(36f, aspect, 0.05f, 40f)
            for (portrait in listOf(false, true)) {
                val fit = RenderMath.fitCamera(proj, portrait)
                assertTrue("distance in search range (aspect=$aspect portrait=$portrait)",
                    fit.distance in 1.5..14.0)
                // eye sits on the fixed direction at 1.5% slack beyond the found distance
                val eyeLen = sqrt(fit.eye.x * fit.eye.x + fit.eye.y * fit.eye.y + fit.eye.z * fit.eye.z)
                assertEquals("slack applied", fit.distance * 1.015, eyeLen, 1e-6)
                assertTrue("pre-slack distance still contains table",
                    allCornersInside(proj, portrait, fit.distance))
                // tightness: coming 3% closer must push at least one corner out
                assertTrue("fit is not tight (aspect=$aspect portrait=$portrait)",
                    !allCornersInside(proj, portrait, fit.distance * 0.97))
            }
        }
    }

    // ---- screen <-> table mapping ----------------------------------------

    private data class Cam(val viewProj: FloatArray, val invViewProj: FloatArray)

    private fun camera(width: Int, height: Int, portrait: Boolean): Cam {
        val proj = Mat4.perspective(36f, width.toFloat() / height, 0.05f, 40f)
        val fit = RenderMath.fitCamera(proj, portrait)
        val viewProj = Mat4.multiply(proj, fit.view)
        return Cam(viewProj, Mat4.inverse(viewProj))
    }

    @Test
    fun tableToScreenAndBackRoundTrips() {
        for (portrait in listOf(false, true)) {
            val (w, h) = if (portrait) 1080 to 2400 else 2400 to 1080
            val cam = camera(w, h, portrait)
            var checked = 0
            var x = -1.15
            while (x <= 1.15) {
                var z = -0.58
                while (z <= 0.58) {
                    val s = RenderMath.tableToScreen(cam.viewProj, w, h, portrait, x, z)
                    assertNotNull("table point must be visible", s)
                    val back = RenderMath.screenToTable(cam.invViewProj, w, h, portrait, s!!.x.toFloat(), s.y.toFloat())
                    assertNotNull("screen point must unproject", back)
                    assertEquals("x round trip ($x,$z portrait=$portrait)", x, back!!.x, 1e-2)
                    assertEquals("z round trip ($x,$z portrait=$portrait)", z, back.y, 1e-2)
                    checked++
                    z += 0.115
                }
                x += 0.23
            }
            assertTrue("grid exercised", checked > 50)
        }
    }

    @Test
    fun screenToTableAndBackRoundTripsForInViewPixels() {
        for (portrait in listOf(false, true)) {
            val (w, h) = if (portrait) 1080 to 1920 else 1920 to 1080
            val cam = camera(w, h, portrait)
            var forward = 0
            var roundTripped = 0
            var py = 0
            while (py <= h) {
                var px = 0
                while (px <= w) {
                    val t = RenderMath.screenToTable(cam.invViewProj, w, h, portrait, px.toFloat(), py.toFloat())
                    if (t != null) {
                        forward++
                        val s = RenderMath.tableToScreen(cam.viewProj, w, h, portrait, t.x, t.y)
                        if (s != null) {
                            assertEquals("px round trip", px.toDouble(), s.x, 1.5)
                            assertEquals("py round trip", py.toDouble(), s.y, 1.5)
                            roundTripped++
                        }
                    }
                    px += w / 12
                }
                py += h / 12
            }
            assertTrue("some pixels must map onto the table", forward > 20)
            assertTrue("most in-view pixels must round trip", roundTripped > forward * 0.9)
        }
    }

    @Test
    fun portraitMappingRotatesTableNinetyDegrees() {
        // the same table corner must land in different screen regions per orientation
        val w = 1920
        val h = 1080
        val landscape = camera(w, h, false)
        val portraitCam = camera(h, w, true)
        val corner = Pair(TableSpec.HALF_LENGTH - 0.1, TableSpec.HALF_WIDTH - 0.1)
        val ls = RenderMath.tableToScreen(landscape.viewProj, w, h, false, corner.first, corner.second)!!
        val ps = RenderMath.tableToScreen(portraitCam.viewProj, h, w, true, corner.first, corner.second)!!
        // rotation swaps the dominant screen axis for this asymmetric corner
        assertTrue("orientations differ", abs(ls.x - ps.x) + abs(ls.y - ps.y) > 50.0)
    }

    @Test
    fun degenerateSurfacesReturnNull() {
        val cam = camera(1920, 1080, false)
        assertNull(RenderMath.screenToTable(cam.invViewProj, 0, 1080, false, 10f, 10f))
        assertNull(RenderMath.screenToTable(cam.invViewProj, 1920, 0, false, 10f, 10f))
        assertNull(RenderMath.tableToScreen(cam.viewProj, 0, 1080, false, 0.0, 0.0))
    }

    // ---- foldable viewport (stage 6) -------------------------------------

    @Test
    fun defaultViewportCoversTheWholeSurface() {
        // Omitting the viewport rect must stay byte-for-byte the old behavior.
        val w = 2400
        val h = 1080
        val cam = camera(w, h, false)
        val sDefault = RenderMath.tableToScreen(cam.viewProj, w, h, false, 0.5, 0.0)
        val sExplicit = RenderMath.tableToScreen(cam.viewProj, w, h, false, 0.5, 0.0, 0, 0, w, h)
        assertNotNull(sDefault)
        assertEquals(sDefault!!.x, sExplicit!!.x, 1e-6)
        assertEquals(sDefault.y, sExplicit.y, 1e-6)
        val tDefault = RenderMath.screenToTable(cam.invViewProj, w, h, false, 300f, 200f)
        val tExplicit = RenderMath.screenToTable(cam.invViewProj, w, h, false, 300f, 200f, 0, 0, w, h)
        assertNotNull(tDefault)
        assertEquals(tDefault!!.x, tExplicit!!.x, 1e-9)
        assertEquals(tDefault.y, tExplicit.y, 1e-9)
    }

    @Test
    fun viewportRestrictedMappingRoundTripsWithinTheViewport() {
        // Foldable tabletop: 1600x1600 surface, table framed into the upper
        // 1600x752 viewport (camera fit uses the viewport aspect).
        val vw = 1600
        val vh = 752
        val cam = camera(vw, vh, false)
        var checked = 0
        var x = -1.15
        while (x <= 1.15) {
            var z = -0.58
            while (z <= 0.58) {
                val s = RenderMath.tableToScreen(cam.viewProj, 1600, 1600, false, x, z, 0, 0, vw, vh)
                assertNotNull("table point must be visible in the viewport", s)
                assertTrue("mapped point stays inside the viewport", s!!.x in 0.0..vw.toDouble() && s.y in 0.0..vh.toDouble())
                val back = RenderMath.screenToTable(cam.invViewProj, 1600, 1600, false, s.x.toFloat(), s.y.toFloat(), 0, 0, vw, vh)
                assertNotNull("viewport point must unproject", back)
                assertEquals("x round trip in viewport", x, back!!.x, 1e-2)
                assertEquals("z round trip in viewport", z, back.y, 1e-2)
                checked++
                z += 0.115
            }
            x += 0.23
        }
        assertTrue("grid exercised", checked > 50)
    }

    @Test
    fun touchesOutsideTheViewportAreRejected() {
        val vw = 1600
        val vh = 752
        val cam = camera(vw, vh, false)
        // Below the viewport (the tabletop control zone) must not map onto the table.
        assertNull(RenderMath.screenToTable(cam.invViewProj, 1600, 1600, false, 800f, 1200f, 0, 0, vw, vh))
        // Just past the right/bottom edges of the viewport rect.
        assertNull(RenderMath.screenToTable(cam.invViewProj, 1600, 1600, false, 1610f, 100f, 0, 0, vw, vh))
        assertNull(RenderMath.screenToTable(cam.invViewProj, 1600, 1600, false, 100f, 762f, 0, 0, vw, vh))
        // Degenerate viewport rect is rejected, not NaN'd.
        assertNull(RenderMath.screenToTable(cam.invViewProj, 1600, 1600, false, 100f, 100f, 0, 0, 0, 752))
        assertNull(RenderMath.tableToScreen(cam.viewProj, 1600, 1600, false, 0.5, 0.0, 0, 0, vw, 0))
    }

    // ---- aim targeting ---------------------------------------------------

    @Test
    fun straightShotContactsBallAtGhostPosition() {
        val t = RenderMath.aimTarget(Vec2(0.0, 0.0), Vec2(1.0, 0.0), balls(1.0 to 0.0))
        assertEquals(1.0 - 2 * r, t.x, 1e-9)
        assertEquals(0.0, t.y, 1e-9)
    }

    @Test
    fun nearestBallOccludesFartherOnes() {
        val t = RenderMath.aimTarget(Vec2(0.0, 0.0), Vec2(1.0, 0.0), balls(0.5 to 0.0, 1.0 to 0.0))
        assertEquals(0.5 - 2 * r, t.x, 1e-9)
    }

    @Test
    fun ballsBehindTheAimAreIgnored() {
        val t = RenderMath.aimTarget(Vec2(0.0, 0.0), Vec2(1.0, 0.0), balls(-1.0 to 0.0))
        assertEquals(maxX, t.x, 1e-9)
        assertEquals(0.0, t.y, 1e-9)
    }

    @Test
    fun inactiveBallsAreIgnored() {
        val t = RenderMath.aimTarget(Vec2(0.0, 0.0), Vec2(1.0, 0.0), balls(0.5 to 0.0, 1.0 to 0.0, inactive = setOf(1)))
        assertEquals(1.0 - 2 * r, t.x, 1e-9)
    }

    @Test
    fun grazingContactTouchesBallAtPerpendicularDistanceTwoR() {
        val t = RenderMath.aimTarget(Vec2(0.0, 0.0), Vec2(1.0, 0.0), balls(1.0 to 2 * r))
        assertEquals(1.0, t.x, 1e-6)
        // ghost ball sits on the aim line; its distance to the ball center is exactly 2r
        assertEquals(2 * r, kotlin.math.hypot(t.x - 1.0, t.y - 2 * r), 1e-9)
    }

    @Test
    fun aimMissingAllBallsClampsToRail() {
        val t = RenderMath.aimTarget(Vec2(0.0, 0.0), Vec2(1.0, 0.0), balls(1.0 to 3 * r))
        assertEquals(maxX, t.x, 1e-9)
        val tz = RenderMath.aimTarget(Vec2(0.0, 0.0), Vec2(0.0, 1.0), balls())
        assertEquals(maxZ, tz.y, 1e-9)
        assertEquals(0.0, tz.x, 1e-9)
    }

    @Test
    fun aimingIntoTheRailFromTheCushionStaysOnTheTable() {
        // Regression: the old fallback placed the ghost ball 1 m outside the table
        // when both rail distances were <= 0 (cue on the cushion aiming outward).
        val corner = Vec2(maxX, maxZ)
        val t = RenderMath.aimTarget(corner, Vec2(1.0, 1.0), balls())
        assertEquals("ghost stays on the cue ball", corner.x, t.x, 1e-12)
        assertEquals(corner.y, t.y, 1e-12)
        assertTrue("target within table bounds", abs(t.x) <= maxX + 1e-9 && abs(t.y) <= maxZ + 1e-9)
    }

    @Test
    fun nearZeroRailDistanceClampsToCushionFace() {
        val cue = Vec2(maxX - 0.01, maxZ - 0.01)
        val t = RenderMath.aimTarget(cue, Vec2(1.0, 1.0), balls())
        // contact at distance 0.01*sqrt(2) lands exactly on the maxX/maxZ cushion faces
        assertEquals(maxX, t.x, 1e-9)
        assertEquals(maxZ, t.y, 1e-9)
    }

    @Test
    fun diagonalAimContactsBallAlongTheAimLine() {
        val d = Vec2(1.0, 1.0)
        val dn = Vec2(1.0 / sqrt(2.0), 1.0 / sqrt(2.0))
        // rail along the diagonal is at ~0.858 m, so use a ball well inside it
        val dist = 0.5
        val t = RenderMath.aimTarget(Vec2(0.0, 0.0), d, balls(dn.x * dist to dn.y * dist))
        assertEquals(dn.x * (dist - 2 * r), t.x, 1e-9)
        assertEquals(dn.y * (dist - 2 * r), t.y, 1e-9)
    }

    @Test
    fun ballBeyondTheRailClampLosesToTheCushion() {
        // a ball farther away than the rail contact must not extend the guide
        val d = Vec2(1.0, 1.0)
        val dn = Vec2(1.0 / sqrt(2.0), 1.0 / sqrt(2.0))
        val t = RenderMath.aimTarget(Vec2(0.0, 0.0), d, balls(dn.x to dn.y))
        assertEquals(maxZ, t.y, 1e-9)
        assertEquals(maxZ, t.x, 1e-9)
    }
}

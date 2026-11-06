package com.minamikune.billiards.physics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TableSpaceTest {
    private val points = listOf(
        Vec2(0.0, 0.0),
        Vec2(0.635, 0.0),
        Vec2(-0.635, 0.0),
        Vec2(0.0, 0.635),
        Vec2(0.0, -0.635),
        Vec2(1.27, 0.42),
        Vec2(-1.27, -0.42),
    )

    @Test
    fun landscapeIsIdentity() {
        for (p in points) {
            val w = TableSpace.tableToWorld(p.x, p.y, portrait = false)
            assertEquals(p.x, w.x, 1e-12)
            assertEquals(p.y, w.y, 1e-12)
            val back = TableSpace.worldToTable(w.x, w.y, portrait = false)
            assertEquals(p.x, back.x, 1e-12)
            assertEquals(p.y, back.y, 1e-12)
        }
    }

    @Test
    fun portraitMatchesRenderedRotation() {
        // The cue ball sits at table (-0.635, 0). A +90 degree world rotation puts it
        // at world (0, +0.635), i.e. near the bottom of a camera looking down +Z.
        val cue = TableSpace.tableToWorld(-0.635, 0.0, portrait = true)
        assertEquals(0.0, cue.x, 1e-12)
        assertEquals(0.635, cue.y, 1e-12)
        val apex = TableSpace.tableToWorld(0.635, 0.0, portrait = true)
        assertEquals(-0.635, apex.y, 1e-12)
    }

    @Test
    fun portraitRoundTripsEveryQuadrant() {
        for (p in points) {
            val w = TableSpace.tableToWorld(p.x, p.y, portrait = true)
            val back = TableSpace.worldToTable(w.x, w.y, portrait = true)
            assertEquals(p.x, back.x, 1e-12)
            assertEquals(p.y, back.y, 1e-12)
        }
    }

    @Test
    fun ballOrientationUsesRendererAxes() {
        val half = kotlin.math.sqrt(0.5)
        // +90 degrees about table +Y (rolling towards +X) becomes -90 degrees
        // about world +Z, which is the visually correct clockwise roll.
        val rolling = TableSpace.orientationToWorld(
            Quat.fromAxisAngle(Vec3.UNIT_Y, Math.PI / 2.0),
        )
        assertEquals(half, rolling.w, 1e-12)
        assertEquals(0.0, rolling.x, 1e-12)
        assertEquals(0.0, rolling.y, 1e-12)
        assertEquals(-half, rolling.z, 1e-12)

        // The racked orientation is a +90 degree turn about table +X; the handedness
        // swap turns it into -90 about renderer +X, preserving the original number-up view.
        val initial = TableSpace.orientationToWorld(Ball.INITIAL_ORIENTATION)
        assertEquals(half, initial.w, 1e-12)
        assertEquals(-half, initial.x, 1e-12)
        assertEquals(0.0, initial.y, 1e-12)
        assertEquals(0.0, initial.z, 1e-12)
    }

    @Test
    fun portraitKeepsTableInsideTheExpectedWorldBounds() {
        for (p in points) {
            val w = TableSpace.tableToWorld(p.x, p.y, portrait = true)
            assertTrue(kotlin.math.abs(w.x) <= TableSpec.HALF_WIDTH + 1e-9)
            assertTrue(kotlin.math.abs(w.y) <= TableSpec.HALF_LENGTH + 1e-9)
        }
    }
}

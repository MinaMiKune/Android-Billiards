package com.minamikune.billiards.gl

import com.minamikune.billiards.physics.TableSpace
import com.minamikune.billiards.physics.TableSpec
import com.minamikune.billiards.physics.Vec2
import com.minamikune.billiards.physics.Vec3
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure rendering math extracted from [TableRenderer] so it can be unit-tested
 * on the JVM. Every function here is free of GL and Android dependencies.
 */
object RenderMath {

    /** Read-only plan-position view of a ball, implemented by [TableRenderer.BallRender]. */
    interface BallXY {
        val x: Double
        val z: Double
        val active: Boolean
    }

    /**
     * Result of fitting the camera so the whole table is visible:
     * [eye] the computed eye position, [view] the corresponding view matrix,
     * [distance] the pre-slack camera distance found by the bisection.
     */
    data class CameraFit(val eye: Vec3, val view: FloatArray, val distance: Double)

    /**
     * Binary-searches a camera distance in [1.5, 14] so that all 8 table corners
     * (at heights 0 and 0.12) project inside the NDC safe zone |x| <= 0.90,
     * |y| <= 0.80, then applies a 1.5% slack. Returns eye/view matrices.
     */
    fun fitCamera(proj: FloatArray, portrait: Boolean): CameraFit {
        val hx = if (portrait) (TableSpec.HALF_WIDTH + 0.20) else (TableSpec.HALF_LENGTH + 0.20)
        val hz = if (portrait) (TableSpec.HALF_LENGTH + 0.20) else (TableSpec.HALF_WIDTH + 0.20)
        val dir = Vec3(0.0, 0.86, 0.51).normalized()
        var lo = 1.5f
        var hi = 14f
        repeat(34) {
            val mid = (lo + hi) * 0.5f
            val e = Vec3(dir.x * mid, dir.y * mid, dir.z * mid)
            val v = Mat4.lookAt(e, Vec3(0.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0))
            val vp = Mat4.multiply(proj, v)
            var ok = true
            for (sx in -1..1 step 2) for (sz in -1..1 step 2) for (y in floatArrayOf(0f, 0.12f)) {
                val p = Mat4.transformPoint(vp, (sx * hx).toFloat(), y, (sz * hz).toFloat())
                if (abs(p.x) > 0.90 || abs(p.y) > 0.80) ok = false
            }
            if (ok) hi = mid else lo = mid
        }
        val d = hi * 1.015f
        val eye = Vec3(dir.x * d, dir.y * d, dir.z * d)
        return CameraFit(eye, Mat4.lookAt(eye, Vec3(0.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0)), hi.toDouble())
    }

    /**
     * Computes the ghost-ball target for the aim guide: the nearest contact
     * point along [aim] from [cue], either with another ball (first contact) or
     * clamped to the rail cushion faces at HALF - BALL_RADIUS.
     */
    fun aimTarget(cue: Vec2, aim: Vec2, balls: List<BallXY>): Vec2 {
        val d = aim.normalized()
        var best = Double.MAX_VALUE
        for (i in 1 until balls.size) {
            val b = balls[i]
            if (!b.active) continue
            val ox = b.x - cue.x
            val oz = b.z - cue.y
            val proj = ox * d.x + oz * d.y
            if (proj <= 0.0) continue
            val perp2 = ox * ox + oz * oz - proj * proj
            val rr = (TableSpec.BALL_RADIUS * 2.0) * (TableSpec.BALL_RADIUS * 2.0)
            if (perp2 > rr) continue
            val t = proj - sqrt(rr - perp2)
            if (t > 0.0 && t < best) best = t
        }
        val maxX = TableSpec.HALF_LENGTH - TableSpec.BALL_RADIUS
        val maxZ = TableSpec.HALF_WIDTH - TableSpec.BALL_RADIUS
        val wall = min(
            when {
                d.x > 1e-6 -> (maxX - cue.x) / d.x
                d.x < -1e-6 -> (-maxX - cue.x) / d.x
                else -> Double.MAX_VALUE
            },
            when {
                d.y > 1e-6 -> (maxZ - cue.y) / d.y
                d.y < -1e-6 -> (-maxZ - cue.y) / d.y
                else -> Double.MAX_VALUE
            },
        )
        best = min(best, wall)
        // Aiming into the rail from the cushion leaves no positive contact
        // distance; clamping to 0 keeps the ghost ball on the cue ball instead
        // of the old fallback that placed it 1 m outside the table.
        if (!best.isFinite()) return cue + d * 1.0
        return cue + d * best.coerceAtLeast(0.0)
    }

    /** Converts a view-space touch into physical table coordinates, or null if it misses. */
    fun screenToTable(invViewProj: FloatArray, width: Int, height: Int, portrait: Boolean, px: Float, py: Float): Vec2? {
        if (width <= 0 || height <= 0) return null
        val x = 2f * px / width - 1f
        val y = 1f - 2f * py / height
        val near = Mat4.transformPoint(invViewProj, x, y, -1f)
        val far = Mat4.transformPoint(invViewProj, x, y, 1f)
        val dy = far.y - near.y
        if (abs(dy) < 1e-6) return null
        val t = -near.y / dy
        if (t < 0.0 || t > 1.0) return null
        val wx = near.x + (far.x - near.x) * t
        val wz = near.z + (far.z - near.z) * t
        // In portrait the whole table is rotated +90 degrees around Y:
        // world = (tableY, 0, -tableX), so tableX = -worldZ and tableY = worldX.
        return TableSpace.worldToTable(wx, wz, portrait)
    }

    fun tableToScreen(viewProj: FloatArray, width: Int, height: Int, portrait: Boolean, x: Double, z: Double): Vec2? {
        if (width <= 0 || height <= 0) return null
        val world = TableSpace.tableToWorld(x, z, portrait)
        val p = Mat4.transformPoint(viewProj, world.x.toFloat(), 0f, world.y.toFloat())
        if (p.z < -1.0 || p.z > 1.0) return null
        return Vec2((p.x + 1.0) * 0.5 * width, (1.0 - p.y) * 0.5 * height)
    }
}

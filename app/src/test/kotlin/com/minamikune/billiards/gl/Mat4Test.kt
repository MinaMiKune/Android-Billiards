package com.minamikune.billiards.gl

import com.minamikune.billiards.physics.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the pure column-major matrix math used by the GL renderer. */
class Mat4Test {

    private fun assertMatEquals(expected: FloatArray, actual: FloatArray, eps: Float = 1e-5f) {
        assertEquals("matrix size", expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("m[$i]", expected[i].toDouble(), actual[i].toDouble(), eps.toDouble())
        }
    }

    private fun assertVecEquals(expected: Vec3, actual: Vec3, eps: Double = 1e-5) {
        assertEquals("x", expected.x, actual.x, eps)
        assertEquals("y", expected.y, actual.y, eps)
        assertEquals("z", expected.z, actual.z, eps)
    }

    @Test
    fun identityLeavesPointsUnchanged() {
        val p = Vec3(1.5, -2.5, 3.25)
        assertVecEquals(p, Mat4.transformPoint(Mat4.identity(), p.x.toFloat(), p.y.toFloat(), p.z.toFloat()))
    }

    @Test
    fun multiplyByIdentityIsNeutral() {
        val rng = Random(42)
        val m = FloatArray(16) { rng.nextFloat() * 4f - 2f }
        assertMatEquals(m, Mat4.multiply(Mat4.identity(), m))
        assertMatEquals(m, Mat4.multiply(m, Mat4.identity()))
    }

    @Test
    fun multiplyAppliesRightHandSideFirst() {
        // column-major: multiply(a, b) == a * b, so b transforms the point first
        val t = Mat4.translate(1f, 2f, 3f)
        val s = Mat4.scale(2f, 4f, 8f)
        val p = Vec3(1.0, 1.0, 1.0)
        val ts = Mat4.multiply(t, s)
        assertVecEquals(Vec3(3.0, 6.0, 11.0), Mat4.transformPoint(ts, 1f, 1f, 1f))
        val st = Mat4.multiply(s, t)
        assertVecEquals(Vec3(4.0, 12.0, 32.0), Mat4.transformPoint(st, 1f, 1f, 1f))
    }

    @Test
    fun rotationsMapBasisVectors() {
        val halfPi = (Math.PI / 2.0).toFloat()
        assertVecEquals(Vec3(0.0, 0.0, -1.0), Mat4.transformPoint(Mat4.rotateY(halfPi), 1f, 0f, 0f))
        assertVecEquals(Vec3(0.0, 1.0, 0.0), Mat4.transformPoint(Mat4.rotateZ(halfPi), 1f, 0f, 0f))
        assertVecEquals(Vec3(0.0, 0.0, 1.0), Mat4.transformPoint(Mat4.rotateX(halfPi), 0f, 1f, 0f))
        // identity rotation at 0
        assertVecEquals(Vec3(1.0, 2.0, 3.0), Mat4.transformPoint(Mat4.rotateY(0f), 1f, 2f, 3f))
    }

    @Test
    fun lookAtMapsEyeToOrigin() {
        val eye = Vec3(3.0, 5.0, 7.0)
        val view = Mat4.lookAt(eye, Vec3(0.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0))
        assertVecEquals(Vec3(0.0, 0.0, 0.0), Mat4.transformPoint(view, 3f, 5f, 7f), 1e-4)
        // inverse maps origin back to the eye
        val inv = Mat4.inverse(view)
        assertVecEquals(eye, Mat4.transformPoint(inv, 0f, 0f, 0f), 1e-3)
    }

    @Test
    fun perspectiveProjectionInverseRoundTripsAcrossRendererAspects() {
        // mirrors TableRenderer.onSurfaceChanged: fov 36, near 0.05, far 40
        val eye = Vec3(0.0, 5.1, 3.3)
        val view = Mat4.lookAt(eye, Vec3(0.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0))
        for (aspect in floatArrayOf(2.4f, 1.9f, 1.78f, 1.33f, 1.0f, 0.56f, 0.46f)) {
            val proj = Mat4.perspective(36f, aspect, 0.05f, 40f)
            val vp = Mat4.multiply(proj, view)
            val inv = Mat4.inverse(vp)
            for (wx in doubleArrayOf(-1.2, 0.0, 1.2)) {
                for (wz in doubleArrayOf(-0.6, 0.0, 0.6)) {
                    val world = Vec3(wx, 0.05, wz)
                    val ndc = Mat4.transformPoint(vp, world.x.toFloat(), world.y.toFloat(), world.z.toFloat())
                    val back = Mat4.transformPoint(inv, ndc.x.toFloat(), ndc.y.toFloat(), ndc.z.toFloat())
                    assertVecEquals(world, back, 2e-3)
                }
            }
        }
    }

    @Test
    fun inverseOfComposedAffineApproximatesIdentity() {
        val rng = Random(7)
        repeat(20) {
            val t = Mat4.translate(rng.nextFloat() * 4f - 2f, rng.nextFloat() * 2f, rng.nextFloat() * 4f - 2f)
            val s = Mat4.scale(0.5f + rng.nextFloat(), 0.5f + rng.nextFloat(), 0.5f + rng.nextFloat())
            val ry = Mat4.rotateY(rng.nextFloat() * 6.28f)
            val m = Mat4.multiply(t, Mat4.multiply(ry, s))
            val inv = Mat4.inverse(m)
            val prod = Mat4.multiply(inv, m)
            assertMatEquals(Mat4.identity(), prod, 1e-4f)
        }
    }

    @Test
    fun fromQuaternionMatchesAxisRotation() {
        val theta = 1.1
        val q = fromQuaternion(Mat4.fromQuaternion(0.0, sin(theta / 2), 0.0, cos(theta / 2)), Mat4.rotateY(theta.toFloat()))
    }

    private fun fromQuaternion(a: FloatArray, b: FloatArray) {
        assertMatEquals(a, b, 1e-6f)
    }

    @Test
    fun fromQuaternionBuildsOrthonormalFrames() {
        val rng = Random(99)
        repeat(50) {
            // random unit quaternion via normalized 4-vector
            var x = rng.nextDouble() * 2 - 1
            var y = rng.nextDouble() * 2 - 1
            var z = rng.nextDouble() * 2 - 1
            var w = rng.nextDouble() * 2 - 1
            val n = kotlin.math.sqrt(x * x + y * y + z * z + w * w)
            x /= n; y /= n; z /= n; w /= n
            val m = Mat4.fromQuaternion(x, y, z, w)
            fun col(c: Int) = Vec3(m[c * 4].toDouble(), m[c * 4 + 1].toDouble(), m[c * 4 + 2].toDouble())
            val c0 = col(0); val c1 = col(1); val c2 = col(2)
            assertEquals("col0 unit", 1.0, c0.length(), 1e-6)
            assertEquals("col1 unit", 1.0, c1.length(), 1e-6)
            assertEquals("col2 unit", 1.0, c2.length(), 1e-6)
            assertEquals("cols orthogonal", 0.0, c0.dot(c1), 1e-6)
            assertEquals("cols orthogonal", 0.0, c0.dot(c2), 1e-6)
            assertEquals("cols orthogonal", 0.0, c1.dot(c2), 1e-6)
            // right-handed: c0 x c1 == c2
            val cross = c0.cross(c1)
            assertVecEquals(c2, cross, 1e-6)
        }
    }

    @Test
    fun inverseOfSingularMatrixReturnsIdentity() {
        val zero = FloatArray(16)
        assertMatEquals(Mat4.identity(), Mat4.inverse(zero))
    }

    @Test
    fun transformPointHandlesPerspectiveDivide() {
        val proj = Mat4.perspective(36f, 1.78f, 0.05f, 40f)
        // a point in front of the camera projects to finite NDC with w != 1
        val p = Mat4.transformPoint(proj, 0f, 0f, -5f)
        assertTrue("expected finite ndc", p.x.isFinite() && p.y.isFinite() && abs(p.z) <= 1.0)
    }
}

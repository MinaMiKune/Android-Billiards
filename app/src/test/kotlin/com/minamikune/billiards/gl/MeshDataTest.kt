package com.minamikune.billiards.gl

import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the pure geometry data behind every Mesh (see [Meshes]).
 * Layout convention (matches Mesh.build's 8-float stride):
 * position(3), normal(3), uv(2).
 */
class MeshDataTest {

    companion object {
        const val STRIDE = 8
    }

    private fun vertexCount(data: Pair<FloatArray, ShortArray>) = data.first.size / STRIDE

    private fun pos(data: Pair<FloatArray, ShortArray>, v: Int): FloatArray {
        val f = data.first
        return floatArrayOf(f[v * STRIDE], f[v * STRIDE + 1], f[v * STRIDE + 2])
    }

    private fun normal(data: Pair<FloatArray, ShortArray>, v: Int): FloatArray {
        val f = data.first
        return floatArrayOf(f[v * STRIDE + 3], f[v * STRIDE + 4], f[v * STRIDE + 5])
    }

    private fun uv(data: Pair<FloatArray, ShortArray>, v: Int): FloatArray {
        val f = data.first
        return floatArrayOf(f[v * STRIDE + 6], f[v * STRIDE + 7])
    }

    private fun assertIndicesValid(data: Pair<FloatArray, ShortArray>) {
        val n = vertexCount(data)
        for (i in data.second) {
            assertTrue("index $i out of range ($n vertices)", i >= 0 && i < n)
        }
        assertEquals("triangle list", 0, data.second.size % 3)
    }

    /** Every triangle must have a non-zero area (except the coincident-pole fans of spheres). */
    private fun assertNoDegenerateTriangles(data: Pair<FloatArray, ShortArray>, skipHeadTris: Int = 0, skipTailTris: Int = 0) {
        val idx = data.second
        val total = idx.size / 3
        var tri = 0
        var i = 0
        while (i < idx.size) {
            if (tri >= skipHeadTris && tri < total - skipTailTris) {
                val a = pos(data, idx[i].toInt())
                val b = pos(data, idx[i + 1].toInt())
                val c = pos(data, idx[i + 2].toInt())
                val e1 = floatArrayOf(b[0] - a[0], b[1] - a[1], b[2] - a[2])
                val e2 = floatArrayOf(c[0] - a[0], c[1] - a[1], c[2] - a[2])
                val cx = e1[1] * e2[2] - e1[2] * e2[1]
                val cy = e1[2] * e2[0] - e1[0] * e2[2]
                val cz = e1[0] * e2[1] - e1[1] * e2[0]
                val area2 = sqrt(cx * cx + cy * cy + cz * cz)
                assertTrue("degenerate triangle at index $i", area2 > 1e-10f)
            }
            tri++
            i += 3
        }
    }

    @Test
    fun sphereHasExpectedCountsAndUnitPositions() {
        for ((rings, segments) in listOf(12 to 24, 24 to 48, 32 to 64)) {
            val data = Meshes.sphereData(rings, segments)
            assertEquals("vertex count", (rings + 1) * (segments + 1), vertexCount(data))
            assertEquals("index count", rings * segments * 6, data.second.size)
            assertIndicesValid(data)
            val row = segments + 1
            for (r in 0..rings) {
                for (s in 0..segments) {
                    val v = r * row + s
                    val p = pos(data, v)
                    val len = sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2])
                    assertEquals("vertex $v on unit sphere", 1.0, len.toDouble(), 1e-4)
                    val n = normal(data, v)
                    assertEquals("normal=position x", p[0].toDouble(), n[0].toDouble(), 1e-5)
                    assertEquals("normal=position y", p[1].toDouble(), n[1].toDouble(), 1e-5)
                    assertEquals("normal=position z", p[2].toDouble(), n[2].toDouble(), 1e-5)
                    val uv = uv(data, v)
                    assertTrue("u in [0,1]", uv[0] in 0f..1f)
                    assertTrue("v in [0,1]", uv[1] in 0f..1f)
                }
            }
            // poles
            val top = pos(data, 0)
            assertEquals(0.0, top[0].toDouble(), 1e-6)
            assertEquals(1.0, top[1].toDouble(), 1e-6)
            assertEquals(0.0, top[2].toDouble(), 1e-6)
            val bottom = pos(data, rings * row)
            assertEquals(0.0, bottom[0].toDouble(), 1e-6)
            assertEquals(-1.0, bottom[1].toDouble(), 1e-6)
            // rows beyond the coincident-pole fans (top and bottom) must be non-degenerate
            assertNoDegenerateTriangles(data, skipHeadTris = 2 * segments, skipTailTris = 2 * segments)
        }
    }

    @Test
    fun sphereLowAndUltraSpecsUsedByRenderer() {
        // lowSphereMesh + ultraSphereMesh in TableRenderer
        assertEquals(24 * 48 * 6, Meshes.sphereData(24, 48).second.size)
        assertEquals(32 * 64 * 6, Meshes.sphereData(32, 64).second.size)
    }

    @Test
    fun boxHasSixFacesWithAxisAlignedNormals() {
        val data = Meshes.boxData()
        assertEquals(24, vertexCount(data))
        assertEquals(36, data.second.size)
        assertIndicesValid(data)
        assertNoDegenerateTriangles(data)
        for (v in 0 until vertexCount(data)) {
            val p = pos(data, v)
            for (k in 0..2) assertTrue("box vertex within unit cube", abs(p[k]) <= 0.5f + 1e-6f)
            val n = normal(data, v)
            val len = sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2])
            assertEquals("unit normal", 1.0, len.toDouble(), 1e-5)
            val axis = n.count { abs(it) > 0.5f }
            assertEquals("axis-aligned normal", 1, axis)
        }
        // winding: geometric triangle normal must agree with the stored face normal
        val idx = data.second
        var i = 0
        while (i < idx.size) {
            val a = pos(data, idx[i].toInt())
            val b = pos(data, idx[i + 1].toInt())
            val c = pos(data, idx[i + 2].toInt())
            val n = normal(data, idx[i].toInt())
            val e1 = floatArrayOf(b[0] - a[0], b[1] - a[1], b[2] - a[2])
            val e2 = floatArrayOf(c[0] - a[0], c[1] - a[1], c[2] - a[2])
            val cx = (e1[1] * e2[2] - e1[2] * e2[1]).toDouble()
            val cy = (e1[2] * e2[0] - e1[0] * e2[2]).toDouble()
            val cz = (e1[0] * e2[1] - e1[1] * e2[0]).toDouble()
            val l = sqrt(cx * cx + cy * cy + cz * cz)
            assertTrue("consistent winding", (cx * n[0] + cy * n[1] + cz * n[2]) / l > 0.99)
            i += 3
        }
    }

    @Test
    fun planeAndQuadAreUnitSquaresFacingUp() {
        for (data in listOf(Meshes.planeData(), Meshes.quadData())) {
            assertEquals(4, vertexCount(data))
            assertEquals(6, data.second.size)
            assertIndicesValid(data)
            assertNoDegenerateTriangles(data)
            for (v in 0 until 4) {
                val p = pos(data, v)
                assertEquals("flat on xz plane", 0.0, p[1].toDouble(), 1e-7)
                assertEquals(1.0, abs(p[0]).toDouble(), 1e-7)
                assertEquals(1.0, abs(p[2]).toDouble(), 1e-7)
                val n = normal(data, v)
                assertEquals(0.0, n[0].toDouble(), 1e-7)
                assertEquals(1.0, n[1].toDouble(), 1e-7)
                assertEquals(0.0, n[2].toDouble(), 1e-7)
                val t = uv(data, v)
                assertTrue("uv at corner", t[0] == 0f || t[0] == 1f)
                assertTrue("uv at corner", t[1] == 0f || t[1] == 1f)
            }
        }
    }

    @Test
    fun discIsAFanOnTheUnitCircle() {
        val data = Meshes.discData(48)
        assertEquals(50, vertexCount(data))
        assertEquals(48 * 3, data.second.size)
        assertIndicesValid(data)
        assertNoDegenerateTriangles(data)
        val center = pos(data, 0)
        assertEquals(0.0, center[0].toDouble(), 1e-7)
        assertEquals(0.0, center[2].toDouble(), 1e-7)
        for (v in 1 until vertexCount(data)) {
            val p = pos(data, v)
            val r = sqrt(p[0] * p[0] + p[2] * p[2])
            assertEquals("ring on unit circle", 1.0, r.toDouble(), 1e-5)
            assertEquals("flat", 0.0, p[1].toDouble(), 1e-7)
        }
    }

    @Test
    fun ringHasInnerAndOuterRadii() {
        val data = Meshes.ringData(0.86f, 64)
        assertEquals(2 * 65, vertexCount(data))
        assertEquals(64 * 6, data.second.size)
        assertIndicesValid(data)
        assertNoDegenerateTriangles(data)
        for (v in 0 until vertexCount(data)) {
            val p = pos(data, v)
            val r = sqrt(p[0] * p[0] + p[2] * p[2])
            val expected = if (v % 2 == 0) 1.0 else 0.86
            assertEquals("radius", expected, r.toDouble(), 1e-5)
        }
    }

    @Test
    fun cueCylinderIsUnitRadiusAlongMinusX() {
        val data = Meshes.cueCylinderData(20)
        assertEquals(2 * 21, vertexCount(data))
        assertEquals(20 * 6, data.second.size)
        assertIndicesValid(data)
        assertNoDegenerateTriangles(data)
        for (v in 0 until vertexCount(data)) {
            val p = pos(data, v)
            assertTrue("x is 0 or -1", p[0] == 0f || p[0] == -1f)
            val r = sqrt(p[1] * p[1] + p[2] * p[2])
            assertEquals("unit radius", 1.0, r.toDouble(), 1e-5)
            val n = normal(data, v)
            assertEquals("radial normal has no x component", 0.0, n[0].toDouble(), 1e-6)
            assertEquals("unit radial normal", 1.0, sqrt(n[1] * n[1] + n[2] * n[2]).toDouble(), 1e-5)
        }
    }
}

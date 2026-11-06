package com.minamikune.billiards.gl

import com.minamikune.billiards.physics.Vec3
import kotlin.math.sqrt

/** Minimal column-major 4x4 matrix math for OpenGL ES. */
object Mat4 {
    fun identity(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )

    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (c in 0 until 4) {
            for (r in 0 until 4) {
                out[c * 4 + r] =
                    a[r] * b[c * 4] +
                        a[4 + r] * b[c * 4 + 1] +
                        a[8 + r] * b[c * 4 + 2] +
                        a[12 + r] * b[c * 4 + 3]
            }
        }
        return out
    }

    fun perspective(fovYDegrees: Float, aspect: Float, near: Float, far: Float): FloatArray {
        val f = 1f / kotlin.math.tan(Math.toRadians(fovYDegrees.toDouble()) / 2.0).toFloat()
        val out = FloatArray(16)
        out[0] = f / aspect
        out[5] = f
        out[10] = (far + near) / (near - far)
        out[11] = -1f
        out[14] = 2f * far * near / (near - far)
        return out
    }

    fun lookAt(eye: Vec3, center: Vec3, up: Vec3): FloatArray {
        val f = (center - eye).normalized()
        val s = f.cross(up).normalized()
        val u = s.cross(f)
        return floatArrayOf(
            s.x.toFloat(), u.x.toFloat(), -f.x.toFloat(), 0f,
            s.y.toFloat(), u.y.toFloat(), -f.y.toFloat(), 0f,
            s.z.toFloat(), u.z.toFloat(), -f.z.toFloat(), 0f,
            -s.dot(eye).toFloat(), -u.dot(eye).toFloat(), f.dot(eye).toFloat(), 1f,
        )
    }

    fun translate(x: Float, y: Float, z: Float): FloatArray {
        val m = identity()
        m[12] = x
        m[13] = y
        m[14] = z
        return m
    }

    fun scale(s: Float): FloatArray {
        val m = identity()
        m[0] = s
        m[5] = s
        m[10] = s
        return m
    }

    fun scale(x: Float, y: Float, z: Float): FloatArray {
        val m = identity()
        m[0] = x
        m[5] = y
        m[10] = z
        return m
    }

    fun rotateY(radians: Float): FloatArray {
        val c = kotlin.math.cos(radians)
        val s = kotlin.math.sin(radians)
        val m = identity()
        m[0] = c
        m[2] = -s
        m[8] = s
        m[10] = c
        return m
    }

    fun rotateX(radians: Float): FloatArray {
        val c = kotlin.math.cos(radians)
        val s = kotlin.math.sin(radians)
        val m = identity()
        m[5] = c
        m[6] = s
        m[9] = -s
        m[10] = c
        return m
    }

    fun rotateZ(radians: Float): FloatArray {
        val c = kotlin.math.cos(radians)
        val s = kotlin.math.sin(radians)
        val m = identity()
        m[0] = c
        m[1] = s
        m[4] = -s
        m[5] = c
        return m
    }

    fun fromQuaternion(x: Double, y: Double, z: Double, w: Double): FloatArray {
        val xx = x * x
        val yy = y * y
        val zz = z * z
        val xy = x * y
        val xz = x * z
        val yz = y * z
        val wx = w * x
        val wy = w * y
        val wz = w * z
        return floatArrayOf(
            (1 - 2 * (yy + zz)).toFloat(), (2 * (xy + wz)).toFloat(), (2 * (xz - wy)).toFloat(), 0f,
            (2 * (xy - wz)).toFloat(), (1 - 2 * (xx + zz)).toFloat(), (2 * (yz + wx)).toFloat(), 0f,
            (2 * (xz + wy)).toFloat(), (2 * (yz - wx)).toFloat(), (1 - 2 * (xx + yy)).toFloat(), 0f,
            0f, 0f, 0f, 1f,
        )
    }

    fun transformPoint(m: FloatArray, x: Float, y: Float, z: Float): Vec3 {
        val w = m[3] * x + m[7] * y + m[11] * z + m[15]
        val iw = if (kotlin.math.abs(w) > 1e-8f) 1f / w else 1f
        return Vec3(
            (m[0] * x + m[4] * y + m[8] * z + m[12]) * iw.toDouble(),
            (m[1] * x + m[5] * y + m[9] * z + m[13]) * iw.toDouble(),
            (m[2] * x + m[6] * y + m[10] * z + m[14]) * iw.toDouble(),
        )
    }

    fun inverse(m: FloatArray): FloatArray {
        val inv = FloatArray(16)
        inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] +
            m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10]
        inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] -
            m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10]
        inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] +
            m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9]
        inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] -
            m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9]
        inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] -
            m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10]
        inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] +
            m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10]
        inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] -
            m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9]
        inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] +
            m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9]
        inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] +
            m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6]
        inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] -
            m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6]
        inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] +
            m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5]
        inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] -
            m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5]
        inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] -
            m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6]
        inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] +
            m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6]
        inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] -
            m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5]
        inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] +
            m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5]

        var det = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12]
        if (kotlin.math.abs(det) < 1e-12f) return identity()
        det = 1f / det
        for (i in 0 until 16) inv[i] *= det
        return inv
    }

    fun normalMatrix(model: FloatArray): FloatArray {
        val m = model.copyOf()
        m[12] = 0f
        m[13] = 0f
        m[14] = 0f
        return m
    }
}

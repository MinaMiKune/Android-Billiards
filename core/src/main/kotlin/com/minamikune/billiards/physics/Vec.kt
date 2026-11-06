package com.minamikune.billiards.physics

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Small immutable 2D vector used by the table plane (x = length, y = width). */
data class Vec2(val x: Double, val y: Double) {
    operator fun plus(other: Vec2) = Vec2(x + other.x, y + other.y)
    operator fun minus(other: Vec2) = Vec2(x - other.x, y - other.y)
    operator fun times(scale: Double) = Vec2(x * scale, y * scale)
    operator fun div(scale: Double) = Vec2(x / scale, y / scale)
    operator fun unaryMinus() = Vec2(-x, -y)

    fun dot(other: Vec2) = x * other.x + y * other.y
    fun cross(other: Vec2) = x * other.y - y * other.x
    fun lengthSquared() = x * x + y * y
    fun length() = sqrt(lengthSquared())
    fun normalized(): Vec2 {
        val len = length()
        return if (len < 1e-12) ZERO else Vec2(x / len, y / len)
    }

    fun rotated(angle: Double): Vec2 {
        val c = cos(angle)
        val s = sin(angle)
        return Vec2(x * c - y * s, x * s + y * c)
    }

    fun perpendicular() = Vec2(-y, x)
    fun distanceTo(other: Vec2) = (this - other).length()
    fun isFinite() = x.isFinite() && y.isFinite()

    companion object {
        val ZERO = Vec2(0.0, 0.0)
        val UNIT_X = Vec2(1.0, 0.0)
        val UNIT_Y = Vec2(0.0, 1.0)
    }
}

/** 3D vector, metres / seconds / radians per second depending on context. */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
    operator fun times(scale: Double) = Vec3(x * scale, y * scale, z * scale)
    operator fun div(scale: Double) = Vec3(x / scale, y / scale, z / scale)
    operator fun unaryMinus() = Vec3(-x, -y, -z)

    fun dot(other: Vec3) = x * other.x + y * other.y + z * other.z
    fun cross(other: Vec3) = Vec3(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x,
    )

    fun lengthSquared() = x * x + y * y + z * z
    fun length() = sqrt(lengthSquared())
    fun normalized(): Vec3 {
        val len = length()
        return if (len < 1e-12) ZERO else Vec3(x / len, y / len, z / len)
    }

    fun horizontalLength() = sqrt(x * x + y * y)
    fun isFinite() = x.isFinite() && y.isFinite() && z.isFinite()
    fun almostZero(epsilon: Double = 1e-7) = abs(x) < epsilon && abs(y) < epsilon && abs(z) < epsilon

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
        val UNIT_X = Vec3(1.0, 0.0, 0.0)
        val UNIT_Y = Vec3(0.0, 1.0, 0.0)
        val UNIT_Z = Vec3(0.0, 0.0, 1.0)
    }
}

/**
 * Unit quaternion. Physical simulation uses it only to keep a visual orientation for
 * each ball; collision behaviour is driven by angular velocity, not by this value.
 */
data class Quat(val w: Double, val x: Double, val y: Double, val z: Double) {
    fun normalized(): Quat {
        val n = sqrt(w * w + x * x + y * y + z * z)
        return if (n < 1e-12) IDENTITY else Quat(w / n, x / n, y / n, z / n)
    }

    operator fun times(other: Quat) = Quat(
        w * other.w - x * other.x - y * other.y - z * other.z,
        w * other.x + x * other.w + y * other.z - z * other.y,
        w * other.y - x * other.z + y * other.w + z * other.x,
        w * other.z + x * other.y - y * other.x + z * other.w,
    )

    /** Integrates dq/dt = 0.5 * omega * q for one fixed step. */
    fun integrate(omega: Vec3, dt: Double): Quat {
        val omegaQuat = Quat(0.0, omega.x, omega.y, omega.z)
        val derivative = omegaQuat * this
        val halfDt = 0.5 * dt
        return Quat(
            w + derivative.w * halfDt,
            x + derivative.x * halfDt,
            y + derivative.y * halfDt,
            z + derivative.z * halfDt,
        ).normalized()
    }

    companion object {
        val IDENTITY = Quat(1.0, 0.0, 0.0, 0.0)

        fun fromAxisAngle(axis: Vec3, radians: Double): Quat {
            val n = axis.normalized()
            val half = radians * 0.5
            val s = sin(half)
            return Quat(cos(half), n.x * s, n.y * s, n.z * s)
        }
    }
}

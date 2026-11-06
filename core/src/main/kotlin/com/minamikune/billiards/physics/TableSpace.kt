package com.minamikune.billiards.physics

/**
 * Conversion between table-space coordinates and the world-space coordinates used by
 * the renderer. The table is laid out in the XZ plane; table +Y maps to world +Z.
 *
 * In portrait the rendered table is rotated +90 degrees around the world Y axis:
 *   worldX = tableY
 *   worldZ = -tableX
 *
 * Keeping this mapping in one place makes touch aiming and screen projection testable
 * without an OpenGL context.
 */
object TableSpace {
    fun tableToWorld(x: Double, y: Double, portrait: Boolean): Vec2 =
        if (portrait) Vec2(y, -x) else Vec2(x, y)

    fun worldToTable(worldX: Double, worldZ: Double, portrait: Boolean): Vec2 =
        if (portrait) Vec2(-worldZ, worldX) else Vec2(worldX, worldZ)

    /**
     * Re-expresses a ball orientation from table axes (x, y, z-up) in renderer axes
     * (x, y-up, z). The axis swap is a reflection, so the rotation angle flips sign:
     * a positive spin about table +Y becomes a clockwise spin about world +Z.
     */
    fun orientationToWorld(q: Quat): Quat = Quat(q.w, -q.x, -q.z, -q.y)
}

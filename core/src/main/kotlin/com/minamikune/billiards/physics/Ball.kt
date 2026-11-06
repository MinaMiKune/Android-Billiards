package com.minamikune.billiards.physics

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A ball on the table. IDs: 0 is the cue ball, 1..7 solids, 8 the eight ball,
 * 9..15 stripes. Positions and velocities are in SI units.
 *
 * The mass is deliberately a property rather than a global constant: the physics
 * supports balls of different mass, even though a standard set is uniform.
 */
class Ball(
    val id: Int,
    position: Vec2,
    val radius: Double = TableSpec.BALL_RADIUS,
    val mass: Double = TableSpec.BALL_MASS,
) {
    var position: Vec2 = position
        internal set
    var velocity: Vec2 = Vec2.ZERO
        internal set
    var angularVelocity: Vec3 = Vec3.ZERO
        internal set
    var orientation: Quat = INITIAL_ORIENTATION
        internal set
    var active: Boolean = true
        internal set
    var asleep: Boolean = false
        internal set
    var pocketIndex: Int = -1
        internal set

    internal var lowMotionTime: Double = 0.0
    internal var previousPosition: Vec2 = position

    val inverseMass: Double get() = if (mass > 0.0) 1.0 / mass else 0.0
    val inertia: Double get() = 0.4 * mass * radius * radius

    fun speed(): Double = velocity.length()
    fun angularSpeed(): Double = angularVelocity.length()
    fun kineticEnergy(): Double =
        0.5 * mass * velocity.lengthSquared() + 0.5 * inertia * angularVelocity.lengthSquared()

    /** Contact-point surface velocity relative to the cloth (horizontal components). */
    fun contactVelocity(): Vec2 = Vec2(
        velocity.x - radius * angularVelocity.y,
        velocity.y + radius * angularVelocity.x,
    )

    internal fun wake() {
        asleep = false
        lowMotionTime = 0.0
    }

    internal fun copyFrom(other: Ball) {
        position = other.position
        velocity = other.velocity
        angularVelocity = other.angularVelocity
        orientation = other.orientation
        active = other.active
        asleep = other.asleep
        pocketIndex = other.pocketIndex
        lowMotionTime = other.lowMotionTime
        previousPosition = other.previousPosition
    }

    companion object {
        // Numbers face the top-down camera on a freshly racked table. This is purely
        // presentational; it has no influence on collisions.
        val INITIAL_ORIENTATION: Quat =
            Quat.fromAxisAngle(Vec3.UNIT_X, Math.PI / 2.0).normalized()
    }
}

/** Static table description shared by physics, rules and rendering. */
object TableSpec {
    /** 9-foot playing surface: 100 x 50 inches. */
    const val LENGTH: Double = 2.54
    const val WIDTH: Double = 1.27
    const val HALF_LENGTH: Double = LENGTH / 2.0
    const val HALF_WIDTH: Double = WIDTH / 2.0

    const val BALL_RADIUS: Double = 0.028575
    const val BALL_MASS: Double = 0.17
    const val GRAVITY: Double = 9.81

    /** Cushion contact height is a little above the ball centre in a real table. */
    const val CUSHION_CONTACT_HEIGHT: Double = 0.63

    const val SLIDING_FRICTION: Double = 0.22
    const val ROLLING_FRICTION: Double = 0.020
    const val SPIN_DECAY_SLIDING: Double = 3.8
    const val SPIN_DECAY_ROLLING: Double = 1.35

    const val BALL_RESTITUTION: Double = 0.95
    const val CUSHION_RESTITUTION: Double = 0.74
    const val BALL_FRICTION: Double = 0.06
    const val CUSHION_FRICTION: Double = 0.18
    const val JAW_RADIUS: Double = 0.012
    const val JAW_RESTITUTION: Double = 0.62

    /** Distance from a corner along a rail where the cushion ends. */
    const val CORNER_MOUTH: Double = 0.105
    /** Half-width of the side pocket opening, measured in ball-centre space. */
    const val SIDE_MOUTH_HALF: Double = 0.072
    const val SIDE_POCKET_RADIUS: Double = 0.075
    const val CORNER_POCKET_RADIUS: Double = 0.090

    /** Hard safety limits to catch numerical explosions. */
    const val MAX_SPEED: Double = 12.0
    const val MAX_ANGULAR_SPEED: Double = 900.0

    const val STOP_SPEED: Double = 0.006
    const val STOP_ANGULAR_SPEED: Double = 0.35
    const val STOP_TIME: Double = 0.32

    val pockets: List<Pocket> = listOf(
        Pocket(0, Vec2(-HALF_LENGTH, -HALF_WIDTH), CORNER_POCKET_RADIUS, true),
        Pocket(1, Vec2(0.0, -HALF_WIDTH), SIDE_POCKET_RADIUS, false),
        Pocket(2, Vec2(HALF_LENGTH, -HALF_WIDTH), CORNER_POCKET_RADIUS, true),
        Pocket(3, Vec2(-HALF_LENGTH, HALF_WIDTH), CORNER_POCKET_RADIUS, true),
        Pocket(4, Vec2(0.0, HALF_WIDTH), SIDE_POCKET_RADIUS, false),
        Pocket(5, Vec2(HALF_LENGTH, HALF_WIDTH), CORNER_POCKET_RADIUS, true),
    )

    /** Cushion jaw points exposed to ball collisions. */
    val jaws: List<Vec2> = buildList {
        add(Vec2(-HALF_LENGTH + CORNER_MOUTH, -HALF_WIDTH))
        add(Vec2(HALF_LENGTH - CORNER_MOUTH, -HALF_WIDTH))
        add(Vec2(-HALF_LENGTH + CORNER_MOUTH, HALF_WIDTH))
        add(Vec2(HALF_LENGTH - CORNER_MOUTH, HALF_WIDTH))
        add(Vec2(-SIDE_MOUTH_HALF, -HALF_WIDTH))
        add(Vec2(SIDE_MOUTH_HALF, -HALF_WIDTH))
        add(Vec2(-SIDE_MOUTH_HALF, HALF_WIDTH))
        add(Vec2(SIDE_MOUTH_HALF, HALF_WIDTH))
        add(Vec2(-HALF_LENGTH, -HALF_WIDTH + CORNER_MOUTH))
        add(Vec2(-HALF_LENGTH, HALF_WIDTH - CORNER_MOUTH))
        add(Vec2(HALF_LENGTH, -HALF_WIDTH + CORNER_MOUTH))
        add(Vec2(HALF_LENGTH, HALF_WIDTH - CORNER_MOUTH))
    }

    fun groupOf(id: Int): BallGroup = when (id) {
        0 -> BallGroup.CUE
        8 -> BallGroup.EIGHT
        in 1..7 -> BallGroup.SOLIDS
        in 9..15 -> BallGroup.STRIPES
        else -> BallGroup.UNKNOWN
    }

    fun isCushionGapX(x: Double): Boolean {
        val ax = abs(x)
        return ax > HALF_LENGTH - CORNER_MOUTH || ax < SIDE_MOUTH_HALF
    }

    fun isCushionGapY(y: Double): Boolean {
        return abs(y) > HALF_WIDTH - CORNER_MOUTH
    }
}

enum class BallGroup { CUE, SOLIDS, STRIPES, EIGHT, UNKNOWN }

data class Pocket(
    val index: Int,
    val center: Vec2,
    val captureRadius: Double,
    val corner: Boolean,
)

/** Deterministic legal 8-ball rack. */
object Rack {
    private val numbers = intArrayOf(
        1,
        9, 2,
        10, 8, 3,
        11, 4, 12, 5,
        13, 6, 15, 14, 7,
    )

    /**
     * Returns ball-id -> position for a standard triangle with the apex at the foot spot.
     * Then [numbers] is a legal-looking rack: apex 1, centre 8, opposite corner groups.
     */
    fun positions(): Map<Int, Vec2> {
        val result = LinkedHashMap<Int, Vec2>()
        val apexX = TableSpec.HALF_LENGTH / 2.0
        val rowGap = 2.0 * TableSpec.BALL_RADIUS * kotlin.math.cos(Math.PI / 6.0)
        var n = 0
        for (row in 0 until 5) {
            val x = apexX + row * rowGap
            for (slot in 0..row) {
                val y = (slot - row / 2.0) * 2.0 * TableSpec.BALL_RADIUS
                result[numbers[n++]] = Vec2(x, y)
            }
        }
        return result
    }

    fun cuePosition(): Vec2 = Vec2(-TableSpec.HALF_LENGTH / 2.0, 0.0)
}

/** True when a position is comfortably inside the cloth and not overlapping a pocket. */
fun isValidCuePlacement(position: Vec2): Boolean {
    val margin = TableSpec.BALL_RADIUS + 0.006
    if (position.x < -TableSpec.HALF_LENGTH + margin || position.x > TableSpec.HALF_LENGTH - margin) return false
    if (position.y < -TableSpec.HALF_WIDTH + margin || position.y > TableSpec.HALF_WIDTH - margin) return false
    for (pocket in TableSpec.pockets) {
        if (position.distanceTo(pocket.center) < pocket.captureRadius + TableSpec.BALL_RADIUS * 0.4) return false
    }
    return true
}

internal fun segmentCircleHit(p0: Vec2, p1: Vec2, center: Vec2, radius: Double): Boolean {
    val d = p1 - p0
    val len2 = d.lengthSquared()
    if (len2 < 1e-12) return p0.distanceTo(center) <= radius
    val t = ((center - p0).dot(d) / len2).coerceIn(0.0, 1.0)
    val closest = p0 + d * t
    return closest.distanceTo(center) <= radius
}

internal fun square(value: Double) = value * value

internal fun clamp(value: Double, min: Double, max: Double): Double =
    if (value < min) min else if (value > max) max else value

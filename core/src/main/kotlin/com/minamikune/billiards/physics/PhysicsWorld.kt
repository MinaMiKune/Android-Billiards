package com.minamikune.billiards.physics

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

sealed interface PhysicsEvent {
    data class BallHit(
        val firstBallId: Int,
        val secondBallId: Int,
        val impactSpeed: Double,
        val position: Vec2,
    ) : PhysicsEvent

    data class CushionHit(
        val ballId: Int,
        val impactSpeed: Double,
        val position: Vec2,
    ) : PhysicsEvent

    data class Pocketed(
        val ballId: Int,
        val pocketIndex: Int,
        val position: Vec2,
    ) : PhysicsEvent

    data object Settled : PhysicsEvent
    data class CueStruck(val speed: Double) : PhysicsEvent
    data object Foul : PhysicsEvent
    data class MatchWon(val humanWon: Boolean) : PhysicsEvent
}

data class Strike(
    val direction: Vec2,
    val speed: Double,
    val sideSpin: Double = 0.0,
    val verticalSpin: Double = 0.0,
)

/**
 * Independent, deterministic billiards physics.
 *
 * The renderer never writes here. All motion is produced by a fixed 480 Hz integration
 * step; callers are expected to feed a fixed accumulator, not raw frame deltas.
 */
class PhysicsWorld {
    val balls: List<Ball> = (0..15).map { id ->
        Ball(
            id = id,
            position = if (id == 0) Rack.cuePosition() else {
                Rack.positions()[id] ?: Rack.cuePosition()
            },
        )
    }

    private val events = ArrayList<PhysicsEvent>(32)
    private var settledNotified = false

    val cueBall: Ball get() = balls[0]

    init {
        resetRack()
    }

    fun resetRack() {
        val rack = Rack.positions()
        for (ball in balls) {
            ball.active = true
            ball.asleep = false
            ball.pocketIndex = -1
            ball.velocity = Vec2.ZERO
            ball.angularVelocity = Vec3.ZERO
            ball.orientation = Ball.INITIAL_ORIENTATION
            ball.position = if (ball.id == 0) Rack.cuePosition() else rack[ball.id] ?: Rack.cuePosition()
            ball.previousPosition = ball.position
            ball.lowMotionTime = 0.0
        }
        settledNotified = false
    }

    fun clearBallsForPractice() = resetRack()

    fun activeBalls(): List<Ball> = balls.filter { it.active }

    fun objectBalls(): List<Ball> = balls.filter { it.active && it.id != 0 }

    fun isMoving(): Boolean = balls.any { it.active && !it.asleep }

    fun hasUnsettledMotion(): Boolean {
        for (ball in balls) {
            if (!ball.active) continue
            if (ball.velocity.length() > TableSpec.STOP_SPEED) return true
            if (ball.angularVelocity.length() > TableSpec.STOP_ANGULAR_SPEED) return true
        }
        return false
    }

    fun drainEvents(): List<PhysicsEvent> {
        if (events.isEmpty()) return emptyList()
        val copy = ArrayList<PhysicsEvent>(events.size)
        copy.addAll(events)
        events.clear()
        return copy
    }

    /**
     * Applies a cue strike. [Strike.sideSpin] and [Strike.verticalSpin] are tip offsets in
     * units of ball radius (legal range roughly -0.5..0.5). The impulse model generates
     * side spin around the vertical axis plus follow/draw around the horizontal axis.
     */
    fun strikeCue(strike: Strike) {
        val cue = cueBall
        if (!cue.active) return
        val direction = strike.direction.normalized()
        if (direction.lengthSquared() < 0.5) return
        val speed = clamp(strike.speed, 0.0, TableSpec.MAX_SPEED)
        cue.wake()
        cue.velocity = direction * speed
        val side = clamp(strike.sideSpin, -0.5, 0.5)
        val vertical = clamp(strike.verticalSpin, -0.5, 0.5)
        // For a horizontal impulse J through a tip offset a (side) and b (vertical):
        // omega = (5 * v / (2R)) * (b * n - a * z), where n is the left normal.
        val normal = direction.perpendicular()
        val spinScale = 5.0 * speed / (2.0 * cue.radius)
        cue.angularVelocity = Vec3(
            x = normal.x * vertical * spinScale,
            y = normal.y * vertical * spinScale,
            z = -side * spinScale,
        )
        settledNotified = false
    }

    /** Places the cue ball after a scratch. Returns false if the spot is invalid. */
    fun placeCueBall(position: Vec2): Boolean {
        if (!isValidCuePlacement(position)) return false
        val cue = cueBall
        for (other in balls) {
            if (other.id == 0 || !other.active) continue
            if (cue.radius + other.radius > distance(position, other.position)) return false
        }
        cue.position = position
        cue.previousPosition = position
        cue.velocity = Vec2.ZERO
        cue.angularVelocity = Vec3.ZERO
        cue.active = true
        cue.pocketIndex = -1
        cue.wake()
        settledNotified = false
        return true
    }

    /** Respot all object balls that have been removed. Used by the practice table. */
    fun restoreObjectBalls() {
        val rack = Rack.positions()
        for (ball in balls) {
            if (ball.id == 0 || ball.active) continue
            ball.position = rack[ball.id] ?: continue
            ball.previousPosition = ball.position
            ball.active = true
            ball.asleep = false
            ball.pocketIndex = -1
            ball.velocity = Vec2.ZERO
            ball.angularVelocity = Vec3.ZERO
            ball.orientation = Ball.INITIAL_ORIENTATION
        }
        settledNotified = false
    }

    /**
     * Advances exactly one fixed physics step. The simulation uses a small step
     * (1/480 s) internally to keep the maximum displacement well below a ball radius.
     */
    fun step(dt: Double) {
        require(dt > 0.0 && dt <= 1.0 / 60.0) { "Physics step out of range: $dt" }
        events.clear()
        for (ball in balls) {
            if (!ball.active || ball.asleep) continue
            ball.previousPosition = ball.position
            integrateBall(ball, dt)
            collideCushions(ball)
            collideJaws(ball)
            if (!ball.position.isFinite() || !ball.velocity.isFinite()) {
                // A numerical failure must never poison the table silently.
                ball.position = Rack.cuePosition()
                ball.velocity = Vec2.ZERO
                ball.angularVelocity = Vec3.ZERO
            }
        }

        // Two solver passes: a ball can be pushed into a second ball after the first pass
        // (common in tight clusters and on the break).
        repeat(2) { resolveBallCollisions() }

        for (ball in balls) {
            if (ball.active) checkPocket(ball)
            if (ball.active) updateSleep(ball, dt)
        }

        if (!isMoving() && !settledNotified) {
            settledNotified = true
            events.add(PhysicsEvent.Settled)
        }
    }

    private fun integrateBall(ball: Ball, dt: Double) {
        val radius = ball.radius
        val contact = ball.contactVelocity()
        val slip = contact.length()
        if (slip > 0.012) {
            val frictionDirection = contact.normalized() * -1.0
            val linearAccel = frictionDirection * (TableSpec.SLIDING_FRICTION * TableSpec.GRAVITY)
            ball.velocity += linearAccel * dt
            // alpha = (5 / 2R) * (a_y, -a_x, 0)
            val factor = 5.0 / (2.0 * radius)
            ball.angularVelocity += Vec3(
                x = linearAccel.y * factor,
                y = -linearAccel.x * factor,
                z = 0.0,
            ) * dt
            ball.angularVelocity = Vec3(
                ball.angularVelocity.x,
                ball.angularVelocity.y,
                ball.angularVelocity.z * exp(-TableSpec.SPIN_DECAY_SLIDING * dt),
            )
        } else {
            // Pure rolling. Re-project the angular velocity so that the contact point is
            // stationary, then apply rolling resistance.
            val speed = ball.velocity.length()
            if (speed > 1e-9) {
                val decel = TableSpec.ROLLING_FRICTION * TableSpec.GRAVITY * dt
                val newSpeed = max(0.0, speed - decel)
                ball.velocity = ball.velocity * (newSpeed / speed)
            }
            ball.angularVelocity = Vec3(
                x = -ball.velocity.y / radius,
                y = ball.velocity.x / radius,
                z = ball.angularVelocity.z * exp(-TableSpec.SPIN_DECAY_ROLLING * dt),
            )
        }

        val speed = ball.velocity.length()
        if (speed > TableSpec.MAX_SPEED) ball.velocity = ball.velocity * (TableSpec.MAX_SPEED / speed)
        val angular = ball.angularVelocity.length()
        if (angular > TableSpec.MAX_ANGULAR_SPEED) {
            ball.angularVelocity = ball.angularVelocity * (TableSpec.MAX_ANGULAR_SPEED / angular)
        }

        ball.position += ball.velocity * dt
        ball.orientation = ball.orientation.integrate(ball.angularVelocity, dt)
    }

    private fun collideCushions(ball: Ball) {
        val p = ball.position
        val r = ball.radius
        val halfW = TableSpec.HALF_LENGTH
        val halfH = TableSpec.HALF_WIDTH
        val limitX = halfW - r
        val limitY = halfH - r

        if (p.y > limitY && !TableSpec.isCushionGapX(p.x)) {
            val penetration = p.y - limitY
            ball.position = Vec2(p.x, limitY)
            if (ball.velocity.y > 0.0) {
                resolveCushion(ball, Vec2(0.0, 1.0), Vec2(0.0, r), ball.velocity.y)
            }
            if (penetration > 0.02) ball.velocity = Vec2(ball.velocity.x * 0.92, ball.velocity.y)
        } else if (p.y < -limitY && !TableSpec.isCushionGapX(p.x)) {
            ball.position = Vec2(p.x, -limitY)
            if (ball.velocity.y < 0.0) {
                resolveCushion(ball, Vec2(0.0, -1.0), Vec2(0.0, -r), -ball.velocity.y)
            }
        }

        val p2 = ball.position
        if (p2.x > limitX && !TableSpec.isCushionGapY(p2.y)) {
            ball.position = Vec2(limitX, p2.y)
            if (ball.velocity.x > 0.0) {
                resolveCushion(ball, Vec2(1.0, 0.0), Vec2(r, 0.0), ball.velocity.x)
            }
        } else if (p2.x < -limitX && !TableSpec.isCushionGapY(p2.y)) {
            ball.position = Vec2(-limitX, p2.y)
            if (ball.velocity.x < 0.0) {
                resolveCushion(ball, Vec2(-1.0, 0.0), Vec2(-r, 0.0), -ball.velocity.x)
            }
        }
    }

    private fun resolveCushion(ball: Ball, outward: Vec2, contact: Vec2, normalSpeed: Double) {
        if (normalSpeed <= 0.0) return
        val normal3 = Vec3(outward.x, outward.y, 0.0)
        val contact3 = Vec3(contact.x, contact.y, 0.0)
        val surfaceVelocity = Vec3(
            ball.velocity.x - ball.angularVelocity.z * contact.y,
            ball.velocity.y + ball.angularVelocity.z * contact.x,
            0.0,
        )
        // Normal impulse: Jn = m (1 + e) vn, applied inward.
        val normalImpulse = (1.0 + TableSpec.CUSHION_RESTITUTION) * normalSpeed * ball.mass
        val tangential = surfaceVelocity - normal3 * surfaceVelocity.dot(normal3)
        val tangentialAmount = tangential.length()
        val frictionImpulse = if (tangentialAmount > 1e-9) {
            min(TableSpec.CUSHION_FRICTION * normalImpulse, tangentialAmount * ball.mass * 0.5)
        } else 0.0

        var impulse = normal3 * -normalImpulse
        if (frictionImpulse > 0.0) {
            impulse += (tangential * (-frictionImpulse / tangentialAmount))
        }
        ball.velocity += Vec2(impulse.x, impulse.y) * (1.0 / ball.mass)
        val torque = contact3.cross(impulse)
        ball.angularVelocity += torque * (1.0 / ball.inertia)
        // Resting on the rail damps vertical spin.
        ball.angularVelocity = Vec3(
            ball.angularVelocity.x,
            ball.angularVelocity.y,
            ball.angularVelocity.z * 0.82,
        )
        ball.wake()
        events.add(
            PhysicsEvent.CushionHit(
                ballId = ball.id,
                impactSpeed = normalSpeed,
                position = ball.position,
            ),
        )
    }

    private fun collideJaws(ball: Ball) {
        for (jaw in TableSpec.jaws) {
            val delta = ball.position - jaw
            val minDistance = ball.radius + TableSpec.JAW_RADIUS
            val dist2 = delta.lengthSquared()
            if (dist2 >= minDistance * minDistance || dist2 < 1e-12) continue
            val dist = sqrt(dist2)
            val normal = delta / dist
            ball.position = jaw + normal * minDistance
            val speedInto = -(ball.velocity.dot(normal))
            if (speedInto > 0.0) {
                val impulse = Vec3(normal.x, normal.y, 0.0) *
                    ((1.0 + TableSpec.JAW_RESTITUTION) * speedInto * ball.mass)
                ball.velocity += Vec2(impulse.x, impulse.y) * (1.0 / ball.mass)
                // The jaw is a static rounded post; spin contributes a little sideways kick.
                val contact = Vec3(normal.x * ball.radius, normal.y * ball.radius, 0.0)
                ball.angularVelocity += contact.cross(impulse) * (1.0 / ball.inertia)
                if (speedInto > 0.05) {
                    events.add(
                        PhysicsEvent.CushionHit(
                            ballId = ball.id,
                            impactSpeed = speedInto,
                            position = ball.position,
                        ),
                    )
                }
            }
            ball.wake()
        }
    }

    private fun resolveBallCollisions() {
        for (i in balls.indices) {
            val a = balls[i]
            if (!a.active) continue
            for (j in i + 1 until balls.size) {
                val b = balls[j]
                if (!b.active) continue
                val delta = b.position - a.position
                val minDist = a.radius + b.radius
                val dist2 = delta.lengthSquared()
                if (dist2 >= minDist * minDist) continue
                var n = Vec2.ZERO
                var dist = 0.0
                if (dist2 < 1e-14) {
                    n = Vec2(1.0, 0.0)
                } else {
                    dist = sqrt(dist2)
                    n = delta / dist
                }
                val overlap = minDist - dist
                a.position -= n * (overlap * 0.5)
                b.position += n * (overlap * 0.5)
                a.wake()
                b.wake()

                val contactA = Vec3(n.x * a.radius, n.y * a.radius, 0.0)
                val contactB = Vec3(-n.x * b.radius, -n.y * b.radius, 0.0)
                val velA = Vec3(a.velocity.x, a.velocity.y, 0.0) + a.angularVelocity.cross(contactA)
                val velB = Vec3(b.velocity.x, b.velocity.y, 0.0) + b.angularVelocity.cross(contactB)
                val relative = velA - velB
                val normalVelocity = relative.dot(Vec3(n.x, n.y, 0.0))
                if (normalVelocity <= 0.0) continue

                val normalImpulse = (1.0 + TableSpec.BALL_RESTITUTION) * normalVelocity /
                    (a.inverseMass + b.inverseMass)
                var impulse = Vec3(n.x, n.y, 0.0) * -normalImpulse

                val tangentVelocity = relative - Vec3(n.x, n.y, 0.0) * normalVelocity
                val tangentSpeed = tangentVelocity.length()
                if (tangentSpeed > 1e-9) {
                    val effectiveMass = 1.0 / (
                        a.inverseMass + b.inverseMass +
                            2.0 * a.radius * a.radius / a.inertia
                        )
                    val frictionLimit = TableSpec.BALL_FRICTION * normalImpulse
                    val friction = min(frictionLimit, tangentSpeed * effectiveMass)
                    impulse += tangentVelocity.normalized() * -friction
                }

                a.velocity += Vec2(impulse.x, impulse.y) * a.inverseMass
                b.velocity -= Vec2(impulse.x, impulse.y) * b.inverseMass
                a.angularVelocity += contactA.cross(impulse) * (1.0 / a.inertia)
                b.angularVelocity += contactB.cross(impulse * -1.0) * (1.0 / b.inertia)

                if (normalVelocity > 0.03) {
                    events.add(
                        PhysicsEvent.BallHit(
                            firstBallId = a.id,
                            secondBallId = b.id,
                            impactSpeed = normalVelocity,
                            position = (a.position + b.position) * 0.5,
                        ),
                    )
                }
            }
        }
    }

    private fun checkPocket(ball: Ball) {
        for (pocket in TableSpec.pockets) {
            if (segmentCircleHit(
                    ball.previousPosition,
                    ball.position,
                    pocket.center,
                    pocket.captureRadius,
                )
            ) {
                ball.active = false
                ball.asleep = true
                ball.pocketIndex = pocket.index
                val resting = ball.position
                ball.position = pocket.center
                ball.velocity = Vec2.ZERO
                ball.angularVelocity = Vec3.ZERO
                events.add(
                    PhysicsEvent.Pocketed(
                        ballId = ball.id,
                        pocketIndex = pocket.index,
                        position = resting,
                    ),
                )
                return
            }
        }

        // Safety net: a ball that squeezes through a mouth without being captured is
        // returned to the nearest legal spot instead of escaping the table forever.
        val escape = 0.06
        if (abs(ball.position.x) > TableSpec.HALF_LENGTH + escape ||
            abs(ball.position.y) > TableSpec.HALF_WIDTH + escape
        ) {
            val pocket = TableSpec.pockets.minByOrNull { it.center.distanceTo(ball.position) }
            if (pocket != null) {
                ball.active = false
                ball.asleep = true
                ball.pocketIndex = pocket.index
                ball.velocity = Vec2.ZERO
                ball.angularVelocity = Vec3.ZERO
                events.add(PhysicsEvent.Pocketed(ball.id, pocket.index, ball.position))
            } else {
                ball.position = Rack.cuePosition()
                ball.velocity = Vec2.ZERO
            }
        }
    }

    private fun updateSleep(ball: Ball, dt: Double) {
        if (ball.velocity.length() <= TableSpec.STOP_SPEED &&
            ball.angularVelocity.length() <= TableSpec.STOP_ANGULAR_SPEED
        ) {
            ball.lowMotionTime += dt
            if (ball.lowMotionTime >= TableSpec.STOP_TIME) {
                ball.velocity = Vec2.ZERO
                ball.angularVelocity = Vec3.ZERO
                ball.asleep = true
            }
        } else {
            ball.lowMotionTime = 0.0
            ball.asleep = false
        }
    }

    /** Restores a save. No validation beyond finite values; callers must validate IDs. */
    fun restore(
        states: List<BallState>,
    ) {
        for (state in states) {
            val ball = balls.getOrNull(state.id) ?: continue
            ball.position = state.position
            ball.previousPosition = state.position
            ball.velocity = state.velocity
            ball.angularVelocity = state.angularVelocity
            ball.orientation = state.orientation
            ball.active = state.active
            ball.asleep = state.asleep
            ball.pocketIndex = state.pocketIndex
            ball.lowMotionTime = 0.0
        }
        settledNotified = false
    }

    fun snapshot(): List<BallState> = balls.map { ball ->
        BallState(
            id = ball.id,
            position = ball.position,
            velocity = ball.velocity,
            angularVelocity = ball.angularVelocity,
            orientation = ball.orientation,
            active = ball.active,
            asleep = ball.asleep,
            pocketIndex = ball.pocketIndex,
        )
    }

    private fun distance(a: Vec2, b: Vec2) = (a - b).length()
}

data class BallState(
    val id: Int,
    val position: Vec2,
    val velocity: Vec2,
    val angularVelocity: Vec3,
    val orientation: Quat,
    val active: Boolean,
    val asleep: Boolean,
    val pocketIndex: Int,
)

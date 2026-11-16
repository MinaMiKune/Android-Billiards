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
            if (!ball.ballActive) continue
            val vx = ball.vx
            val vy = ball.vy
            if (sqrt(vx * vx + vy * vy) > TableSpec.STOP_SPEED) return true
            val wx = ball.wx
            val wy = ball.wy
            val wz = ball.wz
            if (sqrt(wx * wx + wy * wy + wz * wz) > TableSpec.STOP_ANGULAR_SPEED) return true
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
        // The sliding-friction torque is stiff: at large dt a single semi-implicit
        // impulse can overshoot the spin it is damping, flipping the slip direction
        // every step and pumping energy into a two-state limit cycle (observed in
        // stage 3: balls buzzing across the table forever, the table never settling).
        // Integrate internally at the GameLoop's native 1/480 cadence, which the
        // stage-2 fuzz campaign validated, regardless of the caller's cadence.
        val subDt = 1.0 / 480.0
        var remaining = dt
        while (remaining > 1e-12) {
            val h = if (remaining < subDt) remaining else subDt
            integrate(h)
            remaining -= h
        }
    }

    private fun integrate(dt: Double) {
        for (ball in balls) {
            if (!ball.ballActive || ball.ballAsleep) continue
            ball.prevX = ball.px
            ball.prevY = ball.py
            integrateBall(ball, dt)
            collideCushions(ball)
            collideJaws(ball)
            if (!(ball.px.isFinite() && ball.py.isFinite() && ball.vx.isFinite() && ball.vy.isFinite())) {
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
            if (ball.ballActive) checkPocket(ball)
            if (ball.ballActive) updateSleep(ball, dt)
        }

        if (!isMoving() && !settledNotified) {
            settledNotified = true
            events.add(PhysicsEvent.Settled)
        }
    }

    /**
     * Scalar hot path: the previous vector-form implementation is expanded here
     * into primitive Double math with the exact same operation order, so results
     * are bit-identical while a 480 Hz step allocates no temporary vectors.
     */
    private fun integrateBall(ball: Ball, dt: Double) {
        val radius = ball.radius
        var vx = ball.vx
        var vy = ball.vy
        var wx = ball.wx
        var wy = ball.wy
        var wz = ball.wz

        val cx = vx - radius * wy
        val cy = vy + radius * wx
        val slip = sqrt(cx * cx + cy * cy)
        if (slip > 0.012) {
            val mu = TableSpec.SLIDING_FRICTION * TableSpec.GRAVITY
            val ax = -cx / slip * mu
            val ay = -cy / slip * mu
            vx += ax * dt
            vy += ay * dt
            // alpha = (5 / 2R) * (a_y, -a_x, 0)
            val factor = 5.0 / (2.0 * radius)
            wx += ay * factor * dt
            wy += -ax * factor * dt
            wz *= exp(-TableSpec.SPIN_DECAY_SLIDING * dt)
        } else {
            // Pure rolling. Re-project the angular velocity so that the contact point is
            // stationary, then apply rolling resistance.
            val speed = sqrt(vx * vx + vy * vy)
            if (speed > 1e-9) {
                val decel = TableSpec.ROLLING_FRICTION * TableSpec.GRAVITY * dt
                val newSpeed = max(0.0, speed - decel)
                val k = newSpeed / speed
                vx *= k
                vy *= k
            }
            wx = -vy / radius
            wy = vx / radius
            wz *= exp(-TableSpec.SPIN_DECAY_ROLLING * dt)
        }

        val speed = sqrt(vx * vx + vy * vy)
        if (speed > TableSpec.MAX_SPEED) {
            val k = TableSpec.MAX_SPEED / speed
            vx *= k
            vy *= k
        }
        val angular = sqrt(wx * wx + wy * wy + wz * wz)
        if (angular > TableSpec.MAX_ANGULAR_SPEED) {
            val k = TableSpec.MAX_ANGULAR_SPEED / angular
            wx *= k
            wy *= k
            wz *= k
        }

        ball.px += vx * dt
        ball.py += vy * dt
        ball.vx = vx
        ball.vy = vy
        ball.wx = wx
        ball.wy = wy
        ball.wz = wz
        integrateOrientation(ball, wx, wy, wz, dt)
    }

    /** Scalar form of `Quat.integrate` (dq/dt = 0.5 * omega * q), same operation order. */
    private fun integrateOrientation(ball: Ball, wx: Double, wy: Double, wz: Double, dt: Double) {
        val qw = ball.qw
        val qx = ball.qx
        val qy = ball.qy
        val qz = ball.qz
        val halfDt = 0.5 * dt
        val dw = -(wx * qx + wy * qy + wz * qz)
        val dx = wx * qw + wy * qz - wz * qy
        val dy = -wx * qz + wy * qw + wz * qx
        val dz = wx * qy - wy * qx + wz * qw
        var nw = qw + dw * halfDt
        var nx = qx + dx * halfDt
        var ny = qy + dy * halfDt
        var nz = qz + dz * halfDt
        val n = sqrt(nw * nw + nx * nx + ny * ny + nz * nz)
        if (n < 1e-12) {
            nw = 1.0
            nx = 0.0
            ny = 0.0
            nz = 0.0
        } else {
            nw /= n
            nx /= n
            ny /= n
            nz /= n
        }
        ball.qw = nw
        ball.qx = nx
        ball.qy = ny
        ball.qz = nz
    }

    private fun collideCushions(ball: Ball) {
        val r = ball.radius
        val limitX = TableSpec.HALF_LENGTH - r
        val limitY = TableSpec.HALF_WIDTH - r
        val px = ball.px
        val py = ball.py

        if (py > limitY && !TableSpec.isCushionGapX(px)) {
            val penetration = py - limitY
            ball.py = limitY
            if (ball.vy > 0.0) {
                resolveCushion(ball, 0.0, 1.0, 0.0, r, ball.vy)
            }
            if (penetration > 0.02) ball.vx *= 0.92
        } else if (py < -limitY && !TableSpec.isCushionGapX(px)) {
            ball.py = -limitY
            if (ball.vy < 0.0) {
                resolveCushion(ball, 0.0, -1.0, 0.0, -r, -ball.vy)
            }
        }

        val p2x = ball.px
        val p2y = ball.py
        if (p2x > limitX && !TableSpec.isCushionGapY(p2y)) {
            ball.px = limitX
            if (ball.vx > 0.0) {
                resolveCushion(ball, 1.0, 0.0, r, 0.0, ball.vx)
            }
        } else if (p2x < -limitX && !TableSpec.isCushionGapY(p2y)) {
            ball.px = -limitX
            if (ball.vx < 0.0) {
                resolveCushion(ball, -1.0, 0.0, -r, 0.0, -ball.vx)
            }
        }
    }

    /**
     * Scalar form of the cushion impulse. [ox]/[oy] is the outward normal,
     * [cx]/[cy] the contact offset from the ball centre. The 3D cross products
     * of the vector form reduce to z-axis terms here; spin about x/y passes
     * through the tangential friction as impulse z, which is preserved.
     */
    private fun resolveCushion(ball: Ball, ox: Double, oy: Double, cx: Double, cy: Double, normalSpeed: Double) {
        if (normalSpeed <= 0.0) return
        val vx = ball.vx
        val vy = ball.vy
        val wz = ball.wz
        val svx = vx - wz * cy
        val svy = vy + wz * cx
        val svDotN = svx * ox + svy * oy
        val tx = svx - ox * svDotN
        val ty = svy - oy * svDotN
        val tangentialAmount = sqrt(tx * tx + ty * ty)
        // Normal impulse: Jn = m (1 + e) vn, applied inward.
        val normalImpulse = (1.0 + TableSpec.CUSHION_RESTITUTION) * normalSpeed * ball.mass
        var frictionImpulse = 0.0
        if (tangentialAmount > 1e-9) {
            frictionImpulse = min(TableSpec.CUSHION_FRICTION * normalImpulse, tangentialAmount * ball.mass * 0.5)
        }

        var ix = ox * -normalImpulse
        var iy = oy * -normalImpulse
        if (frictionImpulse > 0.0) {
            ix += tx * (-frictionImpulse / tangentialAmount)
            iy += ty * (-frictionImpulse / tangentialAmount)
        }
        val invMass = 1.0 / ball.mass
        ball.vx = vx + ix * invMass
        ball.vy = vy + iy * invMass
        // torque = contact x impulse; x/y terms vanish only for the zero z-spin
        // case, so only the z term is applied here (matches the vector form).
        val invInertia = 1.0 / ball.inertia
        ball.wz = (ball.wz + (cx * iy - cy * ix) * invInertia) * 0.82
        ball.wake()
        events.add(
            PhysicsEvent.CushionHit(
                ballId = ball.id,
                impactSpeed = normalSpeed,
                position = Vec2(ball.px, ball.py),
            ),
        )
    }

    private fun collideJaws(ball: Ball) {
        val minDistance = ball.radius + TableSpec.JAW_RADIUS
        val minDist2 = minDistance * minDistance
        for (jaw in TableSpec.jaws) {
            val jx = jaw.x
            val jy = jaw.y
            val dx = ball.px - jx
            val dy = ball.py - jy
            val dist2 = dx * dx + dy * dy
            if (dist2 >= minDist2 || dist2 < 1e-12) continue
            val dist = sqrt(dist2)
            val nx = dx / dist
            val ny = dy / dist
            ball.px = jx + nx * minDistance
            ball.py = jy + ny * minDistance
            val vx = ball.vx
            val vy = ball.vy
            val speedInto = -(vx * nx + vy * ny)
            if (speedInto > 0.0) {
                val jn = (1.0 + TableSpec.JAW_RESTITUTION) * speedInto * ball.mass
                val ix = nx * jn
                val iy = ny * jn
                val invMass = 1.0 / ball.mass
                ball.vx = vx + ix * invMass
                ball.vy = vy + iy * invMass
                // The jaw is a static rounded post; the contact offset x impulse
                // torque is z-only, giving a little sideways kick from spin.
                val invInertia = 1.0 / ball.inertia
                ball.wz += (nx * ball.radius * iy - ny * ball.radius * ix) * invInertia
                if (speedInto > 0.05) {
                    events.add(
                        PhysicsEvent.CushionHit(
                            ballId = ball.id,
                            impactSpeed = speedInto,
                            position = Vec2(ball.px, ball.py),
                        ),
                    )
                }
            }
            ball.wake()
        }
    }

    /**
     * Scalar pairwise impulse solver. The vector form's 3D cross products reduce
     * to the z torque shown here, except for friction in the presence of x/y
     * spin: the tangential relative velocity then has a z component, so the
     * friction impulse's z term feeds back into wx/wy and must be kept.
     */
    private fun resolveBallCollisions() {
        for (i in balls.indices) {
            val a = balls[i]
            if (!a.ballActive) continue
            for (j in i + 1 until balls.size) {
                val b = balls[j]
                if (!b.ballActive) continue
                val dx = b.px - a.px
                val dy = b.py - a.py
                val minDist = a.radius + b.radius
                val dist2 = dx * dx + dy * dy
                if (dist2 >= minDist * minDist) continue
                var nx = 1.0
                var ny = 0.0
                var dist = 0.0
                if (dist2 >= 1e-14) {
                    dist = sqrt(dist2)
                    nx = dx / dist
                    ny = dy / dist
                }
                val overlap = minDist - dist
                // The rack grid can leave touching pairs a few ULPs inside 2r.
                // Treating that as contact would wake both balls every step and
                // starve the sleep timer, so the table would never settle.
                if (overlap <= 1e-9) continue
                val half = overlap * 0.5
                a.px -= nx * half
                a.py -= ny * half
                b.px += nx * half
                b.py += ny * half
                a.wake()
                b.wake()

                val rax = nx * a.radius
                val ray = ny * a.radius
                val rbx = -nx * b.radius
                val rby = -ny * b.radius
                val vax = a.vx
                val vay = a.vy
                val vbx = b.vx
                val vby = b.vy
                val wax = a.wx
                val way = a.wy
                val waz = a.wz
                val wbx = b.wx
                val wby = b.wy
                val wbz = b.wz
                // vel = v + w x r, expanded component-wise.
                val relx = (vax - waz * ray) - (vbx - wbz * rby)
                val rely = (vay + waz * rax) - (vby + wbz * rbx)
                val relz = (wax * ray - way * rax) - (wbx * rby - wby * rbx)
                val normalVelocity = relx * nx + rely * ny
                if (normalVelocity <= 0.0) continue

                val normalImpulse = (1.0 + TableSpec.BALL_RESTITUTION) * normalVelocity /
                    (a.inverseMass + b.inverseMass)
                var ix = nx * -normalImpulse
                var iy = ny * -normalImpulse

                val tx = relx - nx * normalVelocity
                val ty = rely - ny * normalVelocity
                val tz = relz
                val tangentSpeed = sqrt(tx * tx + ty * ty + tz * tz)
                var iz = 0.0
                if (tangentSpeed > 1e-9) {
                    val effectiveMass = 1.0 / (
                        a.inverseMass + b.inverseMass +
                            2.0 * a.radius * a.radius / a.inertia
                        )
                    val frictionLimit = TableSpec.BALL_FRICTION * normalImpulse
                    val friction = min(frictionLimit, tangentSpeed * effectiveMass)
                    ix += tx / tangentSpeed * -friction
                    iy += ty / tangentSpeed * -friction
                    iz += tz / tangentSpeed * -friction
                }

                val invMa = a.inverseMass
                val invMb = b.inverseMass
                a.vx = vax + ix * invMa
                a.vy = vay + iy * invMa
                b.vx = vbx - ix * invMb
                b.vy = vby - iy * invMb
                // contactA x impulse / contactB x (-impulse): x/y terms come only
                // from the impulse z component (spin carry from friction).
                val invIa = 1.0 / a.inertia
                val invIb = 1.0 / b.inertia
                a.wx = wax + ray * iz * invIa
                a.wy = way - rax * iz * invIa
                a.wz = waz + (rax * iy - ray * ix) * invIa
                b.wx = wbx - rby * iz * invIb
                b.wy = wby + rbx * iz * invIb
                b.wz = wbz + (rby * ix - rbx * iy) * invIb

                if (normalVelocity > 0.03) {
                    events.add(
                        PhysicsEvent.BallHit(
                            firstBallId = a.id,
                            secondBallId = b.id,
                            impactSpeed = normalVelocity,
                            position = Vec2((a.px + b.px) * 0.5, (a.py + b.py) * 0.5),
                        ),
                    )
                }
            }
        }
    }

    private fun checkPocket(ball: Ball) {
        // Scalar form of segmentCircleHit(previousPosition, position, centre, radius).
        val p0x = ball.prevX
        val p0y = ball.prevY
        val p1x = ball.px
        val p1y = ball.py
        val dx = p1x - p0x
        val dy = p1y - p0y
        val len2 = dx * dx + dy * dy
        for (pocket in TableSpec.pockets) {
            val pcx = pocket.center.x
            val pcy = pocket.center.y
            val hit = if (len2 < 1e-12) {
                val ex = p0x - pcx
                val ey = p0y - pcy
                sqrt(ex * ex + ey * ey) <= pocket.captureRadius
            } else {
                val t = (((pcx - p0x) * dx + (pcy - p0y) * dy) / len2).coerceIn(0.0, 1.0)
                val cx = p0x + dx * t
                val cy = p0y + dy * t
                val ex = cx - pcx
                val ey = cy - pcy
                sqrt(ex * ex + ey * ey) <= pocket.captureRadius
            }
            if (hit) {
                ball.ballActive = false
                ball.ballAsleep = true
                ball.ballPocketIndex = pocket.index
                val resting = Vec2(ball.px, ball.py)
                ball.px = pcx
                ball.py = pcy
                ball.vx = 0.0
                ball.vy = 0.0
                ball.wx = 0.0
                ball.wy = 0.0
                ball.wz = 0.0
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
        if (abs(ball.px) > TableSpec.HALF_LENGTH + escape ||
            abs(ball.py) > TableSpec.HALF_WIDTH + escape
        ) {
            val px = ball.px
            val py = ball.py
            val pocket = TableSpec.pockets.minByOrNull {
                val ex = it.center.x - px
                val ey = it.center.y - py
                sqrt(ex * ex + ey * ey)
            }
            if (pocket != null) {
                ball.ballActive = false
                ball.ballAsleep = true
                ball.ballPocketIndex = pocket.index
                ball.vx = 0.0
                ball.vy = 0.0
                ball.wx = 0.0
                ball.wy = 0.0
                ball.wz = 0.0
                events.add(PhysicsEvent.Pocketed(ball.id, pocket.index, Vec2(px, py)))
            } else {
                ball.position = Rack.cuePosition()
                ball.velocity = Vec2.ZERO
            }
        }
    }

    private fun updateSleep(ball: Ball, dt: Double) {
        val vx = ball.vx
        val vy = ball.vy
        val wx = ball.wx
        val wy = ball.wy
        val wz = ball.wz
        if (sqrt(vx * vx + vy * vy) <= TableSpec.STOP_SPEED &&
            sqrt(wx * wx + wy * wy + wz * wz) <= TableSpec.STOP_ANGULAR_SPEED
        ) {
            ball.lowMotionTime += dt
            if (ball.lowMotionTime >= TableSpec.STOP_TIME) {
                ball.vx = 0.0
                ball.vy = 0.0
                ball.wx = 0.0
                ball.wy = 0.0
                ball.wz = 0.0
                ball.ballAsleep = true
            }
        } else {
            ball.lowMotionTime = 0.0
            ball.ballAsleep = false
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

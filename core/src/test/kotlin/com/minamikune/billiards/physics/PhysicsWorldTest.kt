package com.minamikune.billiards.physics

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhysicsWorldTest {
    private val dt = 1.0 / 480.0

    private fun step(world: PhysicsWorld, seconds: Double): List<PhysicsEvent> {
        val result = ArrayList<PhysicsEvent>()
        val count = (seconds / dt).toInt()
        repeat(count) {
            world.step(dt)
            result += world.drainEvents()
        }
        return result
    }

    private fun isolateBall(
        world: PhysicsWorld,
        id: Int,
        position: Vec2,
        velocity: Vec2 = Vec2.ZERO,
        angular: Vec3 = Vec3.ZERO,
    ): Ball {
        for (ball in world.balls) {
            ball.active = ball.id == id
            ball.asleep = false
            ball.velocity = Vec2.ZERO
            ball.angularVelocity = Vec3.ZERO
        }
        val ball = world.balls[id]
        ball.position = position
        ball.previousPosition = position
        ball.velocity = velocity
        ball.angularVelocity = angular
        return ball
    }

    @Test
    fun rackHasNoOverlapAndStaysOnTable() {
        val world = PhysicsWorld()
        val objectBalls = world.balls.filter { it.id != 0 }
        assertEquals(15, objectBalls.size)
        for (i in objectBalls.indices) {
            for (j in i + 1 until objectBalls.size) {
                val distance = objectBalls[i].position.distanceTo(objectBalls[j].position)
                assertTrue(distance >= 2 * TableSpec.BALL_RADIUS - 1e-9, "rack overlap $distance")
            }
            val ball = objectBalls[i]
            assertTrue(abs(ball.position.x) < TableSpec.HALF_LENGTH)
            assertTrue(abs(ball.position.y) < TableSpec.HALF_WIDTH)
        }
        assertEquals(1, world.balls.first { it.id == 1 }.position.x.let { if (it > 0) 1 else 0 })
        val eight = world.balls.first { it.id == 8 }
        assertTrue(eight.position.x > 0.6)
        assertTrue(abs(eight.position.y) < 1e-9)
    }

    @Test
    fun cueStrikeMovesForwardAndEventuallyStops() {
        val world = PhysicsWorld()
        for (ball in world.balls) {
            if (ball.id != 0) ball.active = false
        }
        world.strikeCue(Strike(Vec2.UNIT_X, 3.0))
        assertTrue(world.cueBall.velocity.x > 2.9)
        step(world, 8.0)
        assertTrue(world.cueBall.speed() < TableSpec.STOP_SPEED + 1e-6)
        assertTrue(abs(world.cueBall.position.x) <= TableSpec.HALF_LENGTH)
    }

    @Test
    fun cushionReboundLosesEnergy() {
        val world = PhysicsWorld()
        val ball = isolateBall(world, 3, Vec2(0.32, 0.0), Vec2(0.0, 1.2))
        val events = step(world, 0.8)
        assertTrue(events.any { it is PhysicsEvent.CushionHit && it.ballId == 3 })
        assertTrue(ball.velocity.y < 0.0)
        assertTrue(ball.velocity.length() < 1.2)
        assertTrue(abs(ball.position.y) <= TableSpec.HALF_WIDTH)
    }

    @Test
    fun ballBallCollisionTransfersMomentumWithoutOverlap() {
        val world = PhysicsWorld()
        for (ball in world.balls) ball.active = ball.id == 0 || ball.id == 3
        val cue = world.cueBall
        val target = world.balls[3]
        cue.position = Vec2(-0.2, 0.0)
        cue.previousPosition = cue.position
        cue.velocity = Vec2(2.0, 0.0)
        target.position = Vec2(0.0, 0.0)
        target.previousPosition = target.position
        target.velocity = Vec2.ZERO
        val events = step(world, 0.25)
        assertTrue(events.any { it is PhysicsEvent.BallHit })
        assertTrue(target.velocity.x > 1.0, "target should receive speed, was ${target.velocity}")
        assertTrue(cue.velocity.x < 0.4)
        assertTrue(
            cue.position.distanceTo(target.position) >= 2 * TableSpec.BALL_RADIUS - 0.002,
        )
    }

    @Test
    fun sideSpinChangesCushionReboundDirection() {
        fun rebound(spin: Double): Vec2 {
            val world = PhysicsWorld()
            val ball = isolateBall(
                world,
                5,
                Vec2(0.32, 0.2),
                Vec2(0.0, 1.0),
                Vec3(0.0, 0.0, spin),
            )
            step(world, 1.2)
            return ball.velocity
        }
        val plain = rebound(0.0)
        val withSpin = rebound(30.0)
        assertTrue(abs(plain.x) < 0.1)
        assertTrue(abs(withSpin.x) > 0.01, "side spin should create tangential rebound: $withSpin")
    }

    @Test
    fun slidingFrictionProducesRolling() {
        val world = PhysicsWorld()
        val ball = isolateBall(world, 0, Vec2(-0.5, 0.0), Vec2(2.0, 0.0))
        step(world, 0.05)
        val contactSpeed = ball.contactVelocity().length()
        assertTrue(contactSpeed < 1.99, "friction should reduce slip: $contactSpeed")
        step(world, 2.0)
        assertTrue(ball.contactVelocity().length() < 0.02)
    }

    @Test
    fun cueTopSpinCreatesForwardRotation() {
        val world = PhysicsWorld()
        for (ball in world.balls) if (ball.id != 0) ball.active = false
        world.strikeCue(Strike(Vec2.UNIT_X, 2.0, sideSpin = 0.0, verticalSpin = 0.35))
        assertTrue(world.cueBall.angularVelocity.y > 0.0)
        assertEquals(0.0, world.cueBall.angularVelocity.z, 1e-9)
    }

    @Test
    fun sideSpinCreatesVerticalAngularVelocity() {
        val world = PhysicsWorld()
        for (ball in world.balls) if (ball.id != 0) ball.active = false
        world.strikeCue(Strike(Vec2.UNIT_X, 2.0, sideSpin = 0.35))
        assertTrue(abs(world.cueBall.angularVelocity.z) > 1.0)
    }

    @Test
    fun sidePocketCapturesBall() {
        val world = PhysicsWorld()
        val ball = isolateBall(world, 4, Vec2(0.0, 0.40), Vec2(0.0, 1.0))
        val events = step(world, 1.0)
        assertTrue(events.any { it is PhysicsEvent.Pocketed && it.ballId == 4 })
        assertFalse(ball.active)
    }

    @Test
    fun cornerPocketCapturesFastBall() {
        val world = PhysicsWorld()
        val ball = isolateBall(world, 6, Vec2(0.8, 0.35), Vec2(5.0, 2.6))
        val events = step(world, 1.0)
        assertTrue(events.any { it is PhysicsEvent.Pocketed && it.ballId == 6 }, "events=$events")
        assertFalse(ball.active)
    }

    @Test
    fun simulationIsDeterministic() {
        fun run(): List<Vec2> {
            val world = PhysicsWorld()
            world.strikeCue(Strike(Vec2(1.0, 0.03), 6.0, sideSpin = 0.12, verticalSpin = 0.1))
            step(world, 3.0)
            return world.balls.map { it.position }
        }
        val a = run()
        val b = run()
        assertEquals(a, b)
    }

    @Test
    fun fastBallDoesNotTunnelThroughCushion() {
        val world = PhysicsWorld()
        val ball = isolateBall(world, 2, Vec2(0.0, 0.0), Vec2(0.0, 11.5))
        step(world, 1.0)
        assertTrue(abs(ball.position.y) <= TableSpec.HALF_WIDTH + 1e-6)
    }

    @Test
    fun placeCueBallRejectsOverlapAndPocket() {
        val world = PhysicsWorld()
        for (ball in world.balls) if (ball.id != 0) ball.active = false
        assertTrue(world.placeCueBall(Vec2(-0.4, 0.2)))
        assertFalse(world.placeCueBall(Vec2(TableSpec.HALF_LENGTH, 0.0)))
        world.balls[3].active = true
        world.balls[3].position = Vec2(0.1, 0.1)
        assertFalse(world.placeCueBall(Vec2(0.1, 0.1)))
    }

    @Test
    fun orientationIntegrationStaysUnitLength() {
        val world = PhysicsWorld()
        for (ball in world.balls) if (ball.id != 0) ball.active = false
        world.strikeCue(Strike(Vec2(1.0, 0.4), 4.0, sideSpin = 0.2, verticalSpin = 0.3))
        step(world, 1.0)
        val q = world.cueBall.orientation
        val norm = sqrt(q.w * q.w + q.x * q.x + q.y * q.y + q.z * q.z)
        assertEquals(1.0, norm, 1e-9)
    }
}

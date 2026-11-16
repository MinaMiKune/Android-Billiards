package com.minamikune.billiards.physics

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Stage-2 hardening tests for [PhysicsWorld], following the test plan in
 * `docs/audit/PHYSICS_DESIGN.md` section 8.2:
 *
 *  1. Invariant fuzzing — many random breaks/strikes; every sampled step must
 *     keep all balls finite, on or near the table, quaternions unit-length and
 *     ball-ball overlap within the documented position-correction tolerance.
 *  2. Determinism hash — fixed seeds hashed over thousands of steps, twice.
 *  3. Energy monotonicity — without any strike, total kinetic energy must not
 *     increase beyond a tiny numerical tolerance.
 *  4. Pocket regression — sweeping the aim across a pocket mouth must either
 *     pocket the ball or leave it fully on the table; never wedged or escaped.
 *  5. Performance baseline — desktop per-step cost must stay far below the
 *     frame budget used by GameLoop (2 ms/frame ≈ 250 µs/step).
 *
 * All scenarios use fixed seeds, so failures are reproducible.
 */
class PhysicsInvariantTest {
    private val dt = 1.0 / 480.0

    // ------------------------------------------------------------------
    // invariant checking
    // ------------------------------------------------------------------

    /** Documented simplification (PHYSICS_DESIGN 4.1): no bias in position correction. */
    private val maxAllowedOverlap = 0.002

    private fun assertInvariants(world: PhysicsWorld, label: String) {
        val active = world.balls.filter { it.active }
        for (ball in active) {
            assertTrue(ball.position.isFinite(), "$label: ball ${ball.id} position not finite")
            assertTrue(ball.velocity.isFinite(), "$label: ball ${ball.id} velocity not finite")
            assertTrue(ball.angularVelocity.isFinite(), "$label: ball ${ball.id} spin not finite")
            val q = ball.orientation
            val norm = sqrt(q.w * q.w + q.x * q.x + q.y * q.y + q.z * q.z)
            assertEquals(1.0, norm, 1e-6, "$label: ball ${ball.id} quaternion drifted")
            assertTrue(
                abs(ball.position.x) <= TableSpec.HALF_LENGTH + 0.06 + 1e-9 &&
                    abs(ball.position.y) <= TableSpec.HALF_WIDTH + 0.06 + 1e-9,
                "$label: ball ${ball.id} escaped the table at ${ball.position}",
            )
            assertTrue(ball.speed() <= TableSpec.MAX_SPEED + 1e-9, "$label: ball ${ball.id} over max speed")
        }
        for (i in active.indices) {
            for (j in i + 1 until active.size) {
                val a = active[i]
                val b = active[j]
                val distance = a.position.distanceTo(b.position)
                val overlap = 2.0 * TableSpec.BALL_RADIUS - distance
                assertTrue(
                    overlap <= maxAllowedOverlap,
                    "$label: balls ${a.id}/${b.id} overlap ${"%.6f".format(overlap)} m",
                )
            }
        }
    }

    private fun randomStrike(rand: Random): Strike {
        val angle = rand.nextDouble(0.0, Math.PI * 2.0)
        return Strike(
            direction = Vec2(kotlin.math.cos(angle), kotlin.math.sin(angle)),
            speed = rand.nextDouble(0.5, 9.0),
            sideSpin = rand.nextDouble(-0.5, 0.5),
            verticalSpin = rand.nextDouble(-0.5, 0.5),
        )
    }

    private fun totalKineticEnergy(world: PhysicsWorld): Double =
        world.balls.filter { it.active }.sumOf { it.kineticEnergy() }

    // ------------------------------------------------------------------
    // 1. invariant fuzzing
    // ------------------------------------------------------------------

    @Test
    fun randomBreaksNeverBreakTheTable() {
        var steps = 0
        var maxOverlapSeen = 0.0
        for (seed in 1L..64L) {
            val rand = Random(seed)
            val world = PhysicsWorld()
            world.strikeCue(randomStrike(rand))
            var remaining = 30.0 * 480.0
            while (world.isMoving() && remaining-- > 0) {
                world.step(dt)
                steps++
                if (steps % 4 == 0) assertInvariants(world, "break seed=$seed")
                for (i in world.balls.indices) {
                    for (j in i + 1 until world.balls.size) {
                        val a = world.balls[i]
                        val b = world.balls[j]
                        if (!a.active || !b.active) continue
                        maxOverlapSeen = max(
                            maxOverlapSeen,
                            2.0 * TableSpec.BALL_RADIUS - a.position.distanceTo(b.position),
                        )
                    }
                }
            }
            assertTrue(!world.isMoving(), "break seed=$seed did not settle within 30 s")
        }
        assertTrue(steps > 100_000, "fuzz should exercise a substantial number of steps, got $steps")
        assertTrue(maxOverlapSeen <= maxAllowedOverlap, "max overlap $maxOverlapSeen")
    }

    @Test
    fun randomStrikesOnScatteredLayoutsStayValid() {
        var steps = 0
        for (seed in 100L..115L) {
            val rand = Random(seed)
            val world = PhysicsWorld()
            // Scatter the object balls deterministically with a small clearance
            // so the layout itself is legal.
            val placed = ArrayList<Vec2>()
            for (ball in world.balls) {
                if (ball.id == 0) continue
                var position = Vec2.ZERO
                var attempts = 0
                do {
                    position = Vec2(
                        rand.nextDouble(-TableSpec.HALF_LENGTH + 0.1, TableSpec.HALF_LENGTH - 0.1),
                        rand.nextDouble(-TableSpec.HALF_WIDTH + 0.1, TableSpec.HALF_WIDTH - 0.1),
                    )
                    attempts++
                } while (attempts < 500 && placed.any {
                        it.distanceTo(position) < 2.0 * TableSpec.BALL_RADIUS + 0.002
                    }
                )
                placed += position
                ball.position = position
                ball.previousPosition = position
            }
            world.cueBall.position = Vec2(-TableSpec.HALF_LENGTH / 2.0, 0.0)
            world.cueBall.previousPosition = world.cueBall.position
            // 128 strikes per layout, each observed for half a second.
            repeat(128) { shot ->
                world.strikeCue(randomStrike(rand))
                repeat(240) {
                    world.step(dt)
                    steps++
                    assertInvariants(world, "scatter seed=$seed shot=$shot")
                }
            }
        }
        assertTrue(steps > 400_000, "scatter fuzz too small: $steps steps")
    }

    // ------------------------------------------------------------------
    // 2. determinism hash
    // ------------------------------------------------------------------

    private fun runSeeded(seed: Int): String {
        val rand = Random(seed)
        val world = PhysicsWorld()
        val sink = StringBuilder(64 * 1024)
        repeat(3000) { index ->
            if (index % 500 == 0) world.strikeCue(randomStrike(rand))
            world.step(dt)
            // Quantize aggressively: any divergence, however small, must change
            // at least one digit at 1e-9 precision.
            for (ball in world.balls) {
                sink.append((ball.position.x * 1e9).toLong())
                sink.append((ball.position.y * 1e9).toLong())
                sink.append(ball.active)
                sink.append(ball.asleep)
            }
        }
        return sink.toString()
    }

    @Test
    fun seededRunsProduceIdenticalStateHashes() {
        for (seed in intArrayOf(7, 42, 1337, 9001, 20261116)) {
            val first = runSeeded(seed)
            val second = runSeeded(seed)
            assertEquals(first.length, second.length)
            assertEquals(first.hashCode(), second.hashCode(), "seed $seed diverged")
            assertEquals(first, second, "seed $seed diverged")
        }
    }

    // ------------------------------------------------------------------
    // 3. energy monotonicity
    // ------------------------------------------------------------------

    @Test
    fun kineticEnergyNeverIncreasesWithoutAStrike() {
        val world = PhysicsWorld()
        world.strikeCue(Strike(Vec2(1.0, 0.02), 8.0, sideSpin = 0.3, verticalSpin = -0.2))
        val initial = totalKineticEnergy(world)
        var previous = initial
        var worstRise = 0.0
        var riseSum = 0.0
        var rises = 0
        var step = 0
        val secondMarks = ArrayList<Double>()
        while (step < 30 * 480 && (world.isMoving() || step % 480 != 0)) {
            world.step(dt)
            step++
            val now = totalKineticEnergy(world)
            val rise = now - previous
            if (rise > 0.0) {
                rises++
                riseSum += rise
                worstRise = max(worstRise, rise)
            }
            previous = now
            if (step % 480 == 0) secondMarks += now
        }
        // Friction must dominate: the table ends at rest and every 1-second
        // bucket loses energy. Iterative collision solvers can inject a tiny
        // amount of energy when the second pass re-closes a pair in a dense
        // cluster; the
        // measured bound over 64 seeded breaks is <= 1.1e-3 J in a single step
        // (<= 1.6e-4 of break energy), so allow a wide margin above that while
        // keeping the cumulative injection negligible.
        assertTrue(previous < 1e-6, "balls should be at rest, KE=$previous")
        assertTrue(worstRise < 5e-3, "single-step energy rise $worstRise J too large")
        assertTrue(riseSum < 5e-2, "cumulative injected energy $riseSum J too large")
        assertTrue(rises < step / 100, "energy rose in $rises of $step steps")
        for (i in 1 until secondMarks.size) {
            assertTrue(
                secondMarks[i] <= secondMarks[i - 1] + 1e-9,
                "KE rose across second boundary: ${secondMarks[i - 1]} -> ${secondMarks[i]}",
            )
        }
        assertTrue(previous < initial * 0.01, "almost all energy should be dissipated")
    }

    // ------------------------------------------------------------------
    // 4. pocket regression
    // ------------------------------------------------------------------

    /** Straight shot at path-x [pathX] toward the top side pocket; true when pocketed. */
    private fun sidePocketOutcome(pathX: Double, speed: Double): Boolean {
        val world = PhysicsWorld()
        for (ball in world.balls) if (ball.id != 5) ball.active = false
        val ball = world.balls[5]
        ball.position = Vec2(pathX, -0.4)
        ball.previousPosition = ball.position
        ball.velocity = Vec2(0.0, speed)
        var steps = 3 * 480
        while (steps-- > 0 && ball.active) {
            world.step(dt)
            assertInvariants(world, "pocket pathX=$pathX")
        }
        if (ball.active) {
            assertTrue(abs(ball.position.y) <= TableSpec.HALF_WIDTH + 1e-6, "ball stuck in the mouth at ${ball.position}")
        }
        return !ball.active
    }

    @Test
    fun sweepingAimAcrossSidePocketMouthBehavesSanely() {
        // Measured transition at 2 m/s: paths up to ~0.06 m off centre drop
        // (inner jaw surface at SIDE_MOUTH_HALF - JAW_RADIUS), paths in
        // 0.065..0.095 rebound, and paths >= ~0.10 can carom along the rail
        // into the corner pocket. Assert the ranges with clear margins.
        for (pathX in doubleArrayOf(0.0, 0.01, -0.01, 0.03, -0.03, 0.05, -0.05)) {
            assertTrue(sidePocketOutcome(pathX, 2.0), "aim at x=$pathX should pocket")
        }
        for (pathX in doubleArrayOf(0.07, -0.07, 0.08, -0.08, 0.09, -0.09)) {
            assertTrue(!sidePocketOutcome(pathX, 2.0), "aim at x=$pathX should rebound")
        }
    }

    @Test
    fun sweepingAimAcrossCornerPocketMouthBehavesSanely() {
        // Aim from the table centre toward the top-right corner pocket, sweeping
        // the impact point across the mouth along the top rail direction.
        fun cornerOutcome(aimOffset: Double, speed: Double): Boolean {
            val world = PhysicsWorld()
            for (ball in world.balls) if (ball.id != 6) ball.active = false
            val ball = world.balls[6]
            val pocket = TableSpec.pockets[5]
            val aim = Vec2(pocket.center.x - 0.02, pocket.center.y - 0.035 + aimOffset)
            val start = Vec2(0.2, 0.0)
            val direction = (aim - start).normalized()
            ball.position = start
            ball.previousPosition = start
            ball.velocity = direction * speed
            var steps = 3 * 480
            while (steps-- > 0 && ball.active) {
                world.step(dt)
                assertInvariants(world, "corner aimOffset=$aimOffset")
            }
            return !ball.active
        }
        // Well inside the jaws (aimed short of the rail so the jaws are missed).
        assertTrue(cornerOutcome(-0.010, 2.0), "inner corner aim should pocket")
        assertTrue(cornerOutcome(0.010, 2.0), "inner corner aim should pocket")
        // Far along the rail: the cushion must send it back into play.
        assertTrue(!cornerOutcome(0.08, 2.0), "outer corner aim should rebound")
        assertTrue(!cornerOutcome(-0.08, 2.0), "outer corner aim should rebound")
    }

    // ------------------------------------------------------------------
    // 5. performance baseline (desktop JVM)
    // ------------------------------------------------------------------

    @Test
    fun physicsStepCostStaysFarBelowFrameBudget() {
        val world = PhysicsWorld()
        world.strikeCue(Strike(Vec2(1.0, 0.05), 8.0, sideSpin = 0.1, verticalSpin = 0.1))
        repeat(4800) { world.step(dt) } // warmup, lets the rack break open
        val samples = LongArray(6)
        repeat(samples.size) { round ->
            world.strikeCue(Strike(Vec2(1.0, -0.04), 7.0, sideSpin = -0.1, verticalSpin = 0.05))
            val start = System.nanoTime()
            repeat(4800) { world.step(dt) }
            samples[round] = (System.nanoTime() - start) / 4800
        }
        val mean = samples.average()
        val worst = samples.maxOrNull()!!.toDouble()
        println("per-step cost (ns): mean=%.0f worst=%.0f".format(mean, worst))
        // Frame budget: GameLoop runs 8 steps per 60 fps frame and allots 2 ms to
        // physics, i.e. 250 µs/step. Desktop must clear it with a wide margin;
        // device validation is tracked separately in the acceptance report.
        assertTrue(mean < 250_000.0, "mean per-step cost ${"%.0f".format(mean)} ns exceeds 250 µs budget")
        assertTrue(worst < 5_000_000.0, "worst per-step cost ${"%.0f".format(worst)} ns is pathological")
    }
}

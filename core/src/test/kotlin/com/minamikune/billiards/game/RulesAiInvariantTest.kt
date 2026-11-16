package com.minamikune.billiards.game

import com.minamikune.billiards.physics.BallGroup
import com.minamikune.billiards.physics.PhysicsEvent
import com.minamikune.billiards.physics.PhysicsWorld
import com.minamikune.billiards.physics.Strike
import com.minamikune.billiards.physics.TableSpec
import com.minamikune.billiards.physics.Vec2
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Stage-3 acceptance: systematic invariants over the 8-ball rule engine and the
 * geometry AI, on top of the targeted rule tests in [MatchEngineTest].
 *
 * All tests are seeded so failures reproduce exactly.
 */
class RulesAiInvariantTest {
    private val frame = 1.0 / 60.0

    // ------------------------------------------------------------------ driver

    private data class MatchOutcome(
        val finished: Boolean,
        val winner: Int?,
        val shots: Int,
        val message: String,
        val layoutHash: Long,
    )

    /**
     * Plays a complete VS_AI match with [BilliardsAi] driving *both* players through
     * the public engine API (setAim/setPower/strike/placeCueBall), checking rule
     * invariants between every stroke.
     */
    private inner class MatchRunner(val seed: Long, val maxShots: Int = 240) {
        val engine = MatchEngine(GameMode.VS_AI)
        private val ai = BilliardsAi()
        private var assigned: Pair<BallGroup?, BallGroup?>? = null
        private var inactiveBefore: Set<Int> = emptySet()
        var frames: Long = 0
            private set

        fun run(): MatchOutcome {
            var guard = 0L
            while (engine.state != SessionState.GAME_OVER && engine.shots < maxShots) {
                if (guard++ > 4_000_000) {
                    error("match did not terminate: state=${engine.state} shots=${engine.shots} msg=${engine.message}")
                }
                when (engine.state) {
                    SessionState.AIMING -> {
                        check(engine.currentPlayer == 0) {
                            "AIMING with currentPlayer=1 must not happen (msg=${engine.message})"
                        }
                        humanShoot()
                    }
                    SessionState.BALL_IN_HAND -> humanPlace()
                    SessionState.AI_THINKING, SessionState.SIMULATING -> step()
                    SessionState.GAME_OVER -> Unit
                }
                if (engine.state != SessionState.SIMULATING) checkBetweenShots()
            }
            val snap = engine.snapshot()
            var hash = 1469598103934665603L
            for (b in snap.balls) {
                for (v in doubleArrayOf(b.position.x, b.position.y)) {
                    hash = hash xor (v * 1e9).roundToLong()
                    hash *= 1099511628211L
                }
                hash = hash xor if (b.active) 1L else 0L
                hash *= 1099511628211L
            }
            return MatchOutcome(
                finished = engine.state == SessionState.GAME_OVER,
                winner = engine.winner,
                shots = engine.shots,
                message = engine.message,
                layoutHash = hash,
            )
        }

        private fun step() {
            engine.update(frame)
            frames++
        }

        private fun humanShoot() {
            val shot = ai.chooseShot(engine.world, engine.legalTargets(0), seed * 1_000_003L + engine.shots)
            engine.setAim(shot.direction)
            engine.setPower(shot.power)
            check(engine.strike()) { "human strike rejected in state ${engine.state}" }
        }

        private fun humanPlace() {
            check(engine.currentPlayer == 0) { "BALL_IN_HAND for AI should route via AI_THINKING" }
            val spot = ai.choosePlacement(engine.world, engine.legalTargets(0))
            check(engine.placeCueBall(spot)) { "human ball-in-hand placement rejected at $spot" }
        }

        private fun checkBetweenShots() {
            for (b in engine.world.balls) {
                check(b.position.x.isFinite() && b.position.y.isFinite()) {
                    "non-finite position for ball ${b.id} after shot ${engine.shots}"
                }
            }
            if (engine.state == SessionState.AIMING) {
                check(engine.world.cueBall.active) { "cue ball inactive while AIMING (shot ${engine.shots})" }
                // A freshly placed cue ball is awake but stationary until its first step;
                // only real residual motion (velocity-based) is a violation here.
                check(!engine.world.hasUnsettledMotion()) { "balls still moving while AIMING (shot ${engine.shots})" }
            }
            // Groups, once assigned, are symmetric and never change.
            val p0 = engine.players[0].group
            val p1 = engine.players[1].group
            if (p0 != null && p1 != null) check(p0 != p1) { "players hold the same group: $p0" }
            if (assigned != null) {
                check(p0 == assigned!!.first && p1 == assigned!!.second) {
                    "group assignment changed mid-match: $assigned -> ($p0, $p1)"
                }
            } else if (p0 != null || p1 != null) {
                assigned = p0 to p1
            }
            // The table stays open through the break stroke.
            if (engine.shots == 1 && engine.state != SessionState.GAME_OVER) {
                check(p0 == null && p1 == null) { "groups assigned on the break (open table violated)" }
            }
            // Object balls never come back except the 8-ball re-spot on the break.
            val inactive = engine.world.balls.filter { !it.active && it.id != 0 }.map { it.id }.toSet()
            val reactivated = inactiveBefore - inactive
            check(reactivated.isEmpty() || (reactivated == setOf(8) && engine.shots == 1)) {
                "object balls resurrected outside the break 8-spot: $reactivated (shot ${engine.shots})"
            }
            inactiveBefore = inactive
        }
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun seededAiVsAiMatchesAlwaysTerminateWithAValidWinner() {
        val outcomes = (1L..6L).map { seed ->
            val runner = MatchRunner(seed)
            val outcome = runner.run()
            assertTrue(outcome.finished, "seed=$seed did not reach GAME_OVER: ${outcome.message}")
            assertTrue(outcome.winner == 0 || outcome.winner == 1, "seed=$seed winner=${outcome.winner}")
            assertTrue(!runner.engine.world.balls[8].active, "seed=$seed ended with the 8-ball still on the table")
            assertTrue(outcome.shots in 2..240, "seed=$seed shots=${outcome.shots}")
            outcome
        }
        // Sanity: identical AI mirrors should produce mixed results across seeds;
        // identical seeds must agree (checked separately below).
        assertTrue(outcomes.distinctBy { it.layoutHash }.size >= 2, "all six seeds produced identical layouts")
    }

    @Test
    fun seededMatchesAreDeterministic() {
        val a = MatchRunner(7L).run()
        val b = MatchRunner(7L).run()
        assertEquals(a.winner, b.winner)
        assertEquals(a.shots, b.shots)
        assertEquals(a.layoutHash, b.layoutHash)
        assertEquals(a.message, b.message)
    }

    @Test
    fun randomHumanShotsInPracticeNeverDeadlock() {
        val rng = java.util.Random(20261116L)
        val engine = MatchEngine(GameMode.PRACTICE)
        repeat(100) { shot ->
            val angle = rng.nextDouble() * 2.0 * PI
            engine.setAim(Vec2(cos(angle), sin(angle)))
            engine.setPower(0.1 + rng.nextDouble() * 0.85)
            check(engine.strike()) { "practice strike $shot rejected" }
            var guard = 0
            while (engine.state == SessionState.SIMULATING && guard++ < 60 * 120) engine.update(frame)
            assertTrue(
                engine.state == SessionState.AIMING,
                "shot $shot left practice in state ${engine.state} msg=${engine.message}",
            )
            assertTrue(engine.world.cueBall.active, "shot $shot left the cue ball inactive")
            for (b in engine.world.balls) {
                assertTrue(b.position.x.isFinite() && b.position.y.isFinite(), "shot $shot NaN position")
            }
        }
    }

    @Test
    fun noContactShotIsAFoulWithBallInHand() {
        val engine = MatchEngine(GameMode.VS_AI)
        engine.world.balls.forEach { if (it.id != 0 && it.id != 1) it.active = false }
        val one = engine.world.balls[1]
        one.position = Vec2(-1.05, -0.45)
        one.previousPosition = one.position
        val cue = engine.world.cueBall
        cue.position = Vec2(0.9, 0.45)
        cue.previousPosition = cue.position
        engine.setAim(Vec2(0.6, 0.35)) // away from the only object ball, into the top-right area
        engine.setPower(0.3)
        assertTrue(engine.strike())
        var guard = 0
        while (engine.state == SessionState.SIMULATING && guard++ < 60 * 120) engine.update(frame)
        // The engine routes the AI's ball in hand through AI_THINKING + the ballInHand
        // flag rather than the human-only BALL_IN_HAND state.
        assertEquals(SessionState.AI_THINKING, engine.state, "no-contact must be a foul: ${engine.message}")
        assertTrue(engine.snapshot().ballInHand, "AI must receive ball in hand after the foul")
        assertEquals(1, engine.currentPlayer, "foul must pass the turn")
        assertTrue(engine.message.startsWith("No contact"), "message=${engine.message}")
        // The AI must take the gifted ball in hand and keep the match moving.
        guard = 0
        while (engine.state == SessionState.AI_THINKING && guard++ < 60 * 30) engine.update(frame)
        assertTrue(engine.state == SessionState.SIMULATING || engine.state == SessionState.AIMING)
        assertTrue(engine.shots >= 2, "AI must shoot after receiving ball in hand")
    }

    @Test
    fun scratchIsAFoulAndAiContinuesFromBallInHand() {
        val engine = MatchEngine(GameMode.VS_AI)
        engine.world.balls.forEach { if (it.id != 0) it.active = false }
        val cue = engine.world.cueBall
        cue.position = Vec2(-0.95, -0.40)
        cue.previousPosition = cue.position
        engine.setAim(Vec2(-0.45, -0.30)) // straight into the bottom-left corner pocket
        engine.setPower(0.5)
        assertTrue(engine.strike())
        var guard = 0
        while (engine.state == SessionState.SIMULATING && guard++ < 60 * 120) engine.update(frame)
        assertEquals(SessionState.AI_THINKING, engine.state, "scratch must be a foul: ${engine.message}")
        assertTrue(engine.snapshot().ballInHand, "AI must receive ball in hand after the scratch")
        assertEquals(1, engine.currentPlayer)
        guard = 0
        while (engine.state == SessionState.AI_THINKING && guard++ < 60 * 30) engine.update(frame)
        while (engine.state == SessionState.SIMULATING && guard++ < 60 * 120) engine.update(frame)
        assertTrue(engine.shots >= 2, "AI must shoot after a scratch; state=${engine.state} msg=${engine.message}")
    }

    @Test
    fun aiChosenShotsAlwaysContactABall() {
        val rng = java.util.Random(9917L)
        for (seed in 1..24) {
            val world = scatteredWorld(rng)
            val targets = world.balls.filter { it.active && it.id != 0 }.map { it.id }.toSet()
            assertTrue(targets.isNotEmpty())
            val shot = BilliardsAi().chooseShot(world, targets, seed * 31337L)
            assertTrue(shot.direction.length() > 0.99, "seed=$seed direction not normalized")
            assertTrue(shot.speed in 0.1..8.0, "seed=$seed speed=${shot.speed}")
            world.strikeCue(Strike(shot.direction, shot.speed, 0.0, shot.verticalSpin))
            var firstContact: Int? = null
            var cuePocketed = false
            var guard = 0
            while (guard++ < 480 * 90) {
                world.step(1.0 / 480.0)
                for (event in world.drainEvents()) {
                    when (event) {
                        is PhysicsEvent.BallHit ->
                            if (event.firstBallId == 0 || event.secondBallId == 0) {
                                if (firstContact == null) {
                                    firstContact = if (event.firstBallId == 0) event.secondBallId else event.firstBallId
                                }
                            }
                        is PhysicsEvent.Pocketed -> if (event.ballId == 0) cuePocketed = true
                        else -> Unit
                    }
                }
                if (!world.isMoving()) break
            }
            assertTrue(
                firstContact != null || cuePocketed,
                "seed=$seed AI shot hit nothing (aim=${shot.direction} speed=${shot.speed})",
            )
        }
    }

    @Test
    fun aiDecisionCostStaysFarBelowTurnBudget() {
        val rng = java.util.Random(551L)
        // Warm up so first-call JIT compilation is not charged to the budget.
        repeat(20) {
            val world = scatteredWorld(rng)
            val targets = world.balls.filter { it.active && it.id != 0 }.map { it.id }.toSet()
            BilliardsAi().chooseShot(world, targets, 1L)
        }
        var worst = 0L
        var total = 0L
        var calls = 0
        repeat(120) { round ->
            val world = scatteredWorld(rng)
            val targets = world.balls.filter { it.active && it.id != 0 }.map { it.id }.toSet()
            val ai = BilliardsAi()
            val t0 = System.nanoTime()
            ai.chooseShot(world, targets, round.toLong())
            ai.choosePlacement(world, targets)
            val dt = System.nanoTime() - t0
            total += dt
            calls++
            if (dt > worst) worst = dt
        }
        val mean = total / calls
        assertTrue(mean < 1_000_000, "mean AI decision cost ${mean}ns >= 1ms budget")
        assertTrue(worst < 20_000_000, "worst AI decision cost ${worst}ns >= 20ms budget")
    }

    @Test
    fun restoredMidShotSnapshotDoesNotDeadlock() {
        val engine = MatchEngine(GameMode.VS_AI)
        engine.setAim(Vec2(1.0, 0.02))
        engine.setPower(0.8)
        assertTrue(engine.strike())
        // Let the balls actually move, then persist a snapshot mid-stroke — exactly
        // what process death under memory pressure would capture.
        repeat(20) { engine.update(frame) }
        assertEquals(SessionState.SIMULATING, engine.state)
        val snap = engine.snapshot()

        val restored = MatchEngine(GameMode.VS_AI, startsWithBreak = false)
        restored.restore(snap)
        var guard = 0
        while (restored.state == SessionState.SIMULATING && guard++ < 60 * 120) restored.update(frame)
        assertTrue(
            restored.state != SessionState.SIMULATING,
            "restored match dead-locked in SIMULATING (msg=${restored.message})",
        )
        assertTrue(
            restored.state == SessionState.AIMING || restored.state == SessionState.BALL_IN_HAND ||
                restored.state == SessionState.GAME_OVER,
            "unexpected restored state ${restored.state}",
        )
    }

    // ------------------------------------------------------------------ helpers

    /** De-racks a fresh world and scatters cue + 3..10 object balls with legal spacing. */
    private fun scatteredWorld(rng: java.util.Random): PhysicsWorld {
        val world = PhysicsWorld()
        world.balls.forEach { it.active = false }
        val margin = TableSpec.BALL_RADIUS * 2.2
        val placed = ArrayList<Vec2>()

        fun tryPlace(): Vec2 {
            for (attempt in 0..400) {
                val x = (rng.nextDouble() * 2.0 - 1.0) * (TableSpec.HALF_LENGTH - margin)
                val y = (rng.nextDouble() * 2.0 - 1.0) * (TableSpec.HALF_WIDTH - margin)
                val spot = Vec2(x, y)
                if (placed.any { it.distanceTo(spot) < 2.2 * TableSpec.BALL_RADIUS }) continue
                var nearPocket = false
                for (p in TableSpec.pockets) {
                    if (spot.distanceTo(p.center) < p.captureRadius + TableSpec.BALL_RADIUS) {
                        nearPocket = true
                        break
                    }
                }
                if (nearPocket) continue
                placed.add(spot)
                return spot
            }
            error("could not scatter ball after 400 attempts")
        }

        val cueSpot = tryPlace()
        world.cueBall.position = cueSpot
        world.cueBall.previousPosition = cueSpot
        world.cueBall.active = true
        world.cueBall.asleep = true
        world.cueBall.velocity = Vec2.ZERO

        val count = 3 + rng.nextInt(8)
        val ids = (1..15).shuffled(rng).take(count)
        for (id in ids) {
            val ball = world.balls[id]
            val spot = tryPlace()
            ball.position = spot
            ball.previousPosition = spot
            ball.active = true
            ball.asleep = true
            ball.velocity = Vec2.ZERO
        }
        return world
    }
}

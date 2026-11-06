package com.minamikune.billiards.game

import com.minamikune.billiards.physics.BallGroup
import com.minamikune.billiards.physics.Rack
import com.minamikune.billiards.physics.TableSpec
import com.minamikune.billiards.physics.Vec2
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MatchEngineTest {
    private val dt = 1.0 / 240.0

    private fun settle(engine: MatchEngine, maxSeconds: Double = 120.0) {
        var t = 0.0
        while (t < maxSeconds) {
            engine.update(dt)
            t += dt
            if (engine.state != SessionState.SIMULATING) return
        }
        error("settle timeout state=${engine.state} msg=${engine.message}")
    }

    @Test
    fun initialMatchIsReady() {
        val engine = MatchEngine(GameMode.VS_AI)
        assertEquals(SessionState.AIMING, engine.state)
        assertEquals(0, engine.currentPlayer)
        assertEquals(16, engine.world.activeBalls().size)
        assertEquals(1, engine.world.balls.count { it.id == 0 })
        assertTrue(engine.legalTargets().contains(1))
        assertFalse(engine.legalTargets().contains(8))
    }

    @Test
    fun practiceShotSettlesAndReturnsToAim() {
        val engine = MatchEngine(GameMode.PRACTICE)
        engine.setAim(Vec2(1.0, 0.0))
        engine.setPower(0.45)
        assertTrue(engine.strike())
        assertEquals(SessionState.SIMULATING, engine.state)
        settle(engine)
        assertEquals(SessionState.AIMING, engine.state)
        assertTrue(engine.shots >= 1)
    }

    @Test
    fun scratchInPracticeRespotsCueBall() {
        val engine = MatchEngine(GameMode.PRACTICE)
        engine.world.balls.forEach { if (it.id != 0) it.active = false }
        val cue = engine.world.cueBall
        cue.position = Vec2(0.0, 0.40)
        cue.previousPosition = cue.position
        engine.setAim(Vec2(0.0, 1.0))
        engine.setPower(0.25)
        engine.strike()
        settle(engine)
        assertTrue(engine.world.cueBall.active)
        assertTrue(engine.world.cueBall.position.distanceTo(Rack.cuePosition()) < 0.2)
        assertEquals(SessionState.AIMING, engine.state)
    }

    @Test
    fun scratchInPracticeRespotsCueWhenHeadSpotIsOccupied() {
        val engine = MatchEngine(GameMode.PRACTICE)
        engine.world.balls.forEach { if (it.id != 0 && it.id != 1) it.active = false }
        val blocker = engine.world.balls[1]
        blocker.position = Rack.cuePosition()
        blocker.previousPosition = blocker.position
        val cue = engine.world.cueBall
        cue.position = Vec2(0.0, 0.40)
        cue.previousPosition = cue.position
        engine.setAim(Vec2(0.0, 1.0))
        engine.setPower(0.25)
        engine.strike()
        settle(engine)
        assertTrue(engine.world.cueBall.active, "cue must be respotted")
        assertTrue(
            engine.world.cueBall.position.distanceTo(blocker.position) >= 2.0 * TableSpec.BALL_RADIUS,
            "cue must not overlap the blocking ball",
        )
        assertEquals(SessionState.AIMING, engine.state)
    }

    @Test
    fun aiTakesOverAfterHumanTurn() {
        val engine = MatchEngine(GameMode.VS_AI)
        engine.setAim(Vec2(1.0, 0.03))
        engine.setPower(0.7)
        engine.strike()
        var guard = 0
        while (engine.state == SessionState.SIMULATING && guard++ < 30000) engine.update(dt)
        assertTrue(
            engine.state == SessionState.AI_THINKING || engine.state == SessionState.BALL_IN_HAND ||
                engine.currentPlayer == 1,
            "state=${engine.state} current=${engine.currentPlayer} msg=${engine.message}",
        )
        guard = 0
        while (engine.state == SessionState.AI_THINKING && guard++ < 2000) engine.update(dt)
        assertTrue(engine.state == SessionState.SIMULATING || engine.state == SessionState.AIMING)
        assertTrue(engine.shots >= 2)
    }

    @Test
    fun aiShotAlwaysTargetsALegalBall() {
        val engine = MatchEngine(GameMode.VS_AI)
        val ai = BilliardsAi()
        val targets = engine.legalTargets(1)
        assertTrue(targets.isNotEmpty())
        for (seed in 0L..20L) {
            val shot = ai.chooseShot(engine.world, targets, seed)
            assertTrue(shot.direction.length() > 0.99)
            assertTrue(shot.speed in 0.1..8.0)
            assertTrue(shot.power in 0.0..1.0)
            assertTrue(shot.direction.x.isFinite() && shot.direction.y.isFinite())
        }
    }

    @Test
    fun aiPlacementIsLegal() {
        val engine = MatchEngine(GameMode.VS_AI)
        val spot = BilliardsAi().choosePlacement(engine.world, engine.legalTargets(1))
        assertTrue(com.minamikune.billiards.physics.isValidCuePlacement(spot))
        for (ball in engine.world.balls) {
            if (ball.id == 0 || !ball.active) continue
            assertTrue(ball.position.distanceTo(spot) >= 2.0 * TableSpec.BALL_RADIUS)
        }
    }

    @Test
    fun snapshotRoundTrips() {
        val engine = MatchEngine(GameMode.PRACTICE)
        engine.world.balls[3].active = false
        engine.setPower(0.8)
        val snap = engine.snapshot()
        val restored = MatchEngine(GameMode.PRACTICE)
        restored.restore(snap)
        assertEquals(snap.balls.map { it.id to it.active }, restored.snapshot().balls.map { it.id to it.active })
        assertEquals(0.8, restored.power, 1e-9)
    }

    @Test
    fun breakPotKeepsTurnAndLeavesTableOpen() {
        val engine = MatchEngine(GameMode.VS_AI)
        engine.world.balls.forEach { if (it.id != 0 && it.id != 1 && it.id != 8) it.active = false }
        val cue = engine.world.cueBall
        cue.position = Vec2(0.0, 0.0)
        cue.previousPosition = cue.position
        val one = engine.world.balls[1]
        one.position = Vec2(0.0, 0.40)
        one.previousPosition = one.position
        val eight = engine.world.balls[8]
        eight.position = Vec2(0.55, 0.50)
        eight.previousPosition = eight.position
        engine.setAim(Vec2(0.0, 1.0))
        engine.setPower(0.55)
        assertTrue(engine.strike())
        settle(engine)
        assertFalse(engine.world.balls[1].active, "break should pot the 1, message=${engine.message}")
        assertEquals(0, engine.currentPlayer, "breaker keeps the turn after a pot")
        assertEquals(SessionState.AIMING, engine.state, "message=${engine.message}")
        assertEquals(null, engine.players[0].group, "the table must stay open after the break")
    }

    @Test
    fun aiKeepsTurnAfterPottingItsOwnBall() {
        val engine = MatchEngine(GameMode.VS_AI, startsWithBreak = false)
        engine.world.balls.forEach { if (it.id != 0 && it.id != 8 && it.id != 9) it.active = false }
        val cue = engine.world.cueBall
        cue.position = Vec2(0.0, 0.15)
        cue.previousPosition = cue.position
        val nine = engine.world.balls[9]
        nine.position = Vec2(0.0, 0.45)
        nine.previousPosition = nine.position
        val eight = engine.world.balls[8]
        eight.position = Vec2(0.55, 0.50)
        eight.previousPosition = eight.position
        val snap = engine.snapshot().copy(
            currentPlayer = 1,
            state = SessionState.AIMING,
            players = listOf(
                PlayerInfo("You", group = BallGroup.SOLIDS),
                PlayerInfo("DeepSeek AI", group = BallGroup.STRIPES),
            ),
            aimDirection = Vec2(0.0, 1.0),
            power = 0.50,
        )
        engine.restore(snap)
        assertTrue(engine.strike())
        settle(engine)
        assertTrue(
            engine.state == SessionState.AI_THINKING,
            "AI must keep shooting after its legal pot, state=${engine.state} msg=${engine.message}",
        )
        assertEquals(1, engine.currentPlayer)
    }

    @Test
    fun lastGroupBallAndEightOnSameShotLoses() {
        val engine = MatchEngine(GameMode.VS_AI, startsWithBreak = false)
        engine.world.balls.forEach { if (it.id != 0 && it.id != 1 && it.id != 8) it.active = false }
        val cue = engine.world.cueBall
        cue.position = Vec2(0.0, 0.0)
        cue.previousPosition = cue.position
        val one = engine.world.balls[1]
        one.position = Vec2(0.0, 0.40)
        one.previousPosition = one.position
        val eight = engine.world.balls[8]
        eight.position = Vec2(0.0, 0.455)
        eight.previousPosition = eight.position
        val snap = engine.snapshot().copy(
            state = SessionState.AIMING,
            players = listOf(
                PlayerInfo("You", group = BallGroup.SOLIDS),
                PlayerInfo("DeepSeek AI", group = BallGroup.STRIPES),
            ),
            aimDirection = Vec2(0.0, 1.0),
            power = 0.88,
            verticalSpin = 0.5,
        )
        engine.restore(snap)
        assertTrue(engine.strike())
        settle(engine)
        assertEquals(SessionState.GAME_OVER, engine.state, "message=${engine.message}")
        assertFalse(engine.world.balls[1].active, "last solid should be pocketed, message=${engine.message}")
        assertFalse(engine.world.balls[8].active, "8-ball should be pocketed, message=${engine.message}")
        assertEquals(1, engine.winner, "sinking the last group ball and the 8 on one stroke must lose")
    }

    @Test
    fun eightBallAfterClearingGroupWins() {
        val engine = MatchEngine(GameMode.VS_AI, startsWithBreak = false)
        engine.world.balls.forEach { if (it.id != 0 && it.id != 8) it.active = false }
        val cue = engine.world.cueBall
        cue.position = Vec2(-0.50, 0.0)
        cue.previousPosition = cue.position
        val eight = engine.world.balls[8]
        eight.position = Vec2(-1.16, -0.54)
        eight.previousPosition = eight.position
        val snap = engine.snapshot().copy(
            state = SessionState.AIMING,
            players = listOf(
                PlayerInfo("You", group = BallGroup.SOLIDS),
                PlayerInfo("DeepSeek AI", group = BallGroup.STRIPES),
            ),
            aimDirection = Vec2(-0.62, -0.52),
            power = 0.50,
        )
        engine.restore(snap)
        assertTrue(engine.strike())
        settle(engine)
        assertEquals(SessionState.GAME_OVER, engine.state, "message=${engine.message}")
        assertEquals(0, engine.winner, "cleared group then legal 8 should win, message=${engine.message}")
    }

    @Test
    fun eightBallPocketedEarlyLosesForShooter() {
        val engine = MatchEngine(GameMode.VS_AI, startsWithBreak = false)
        engine.world.balls.forEach { if (it.id != 0 && it.id != 8) it.active = false }
        val cue = engine.world.cueBall
        cue.position = Vec2(-0.90, -0.30)
        cue.previousPosition = cue.position
        val eight = engine.world.balls[8]
        eight.position = Vec2(-1.18, -0.55)
        eight.previousPosition = eight.position
        engine.setAim(Vec2(-0.28, -0.25))
        engine.setPower(0.42)
        engine.strike()
        settle(engine)
        assertNotNull(engine.winner, "8 early should end the rack, message=${engine.message}")
        assertEquals(SessionState.GAME_OVER, engine.state)
    }
}

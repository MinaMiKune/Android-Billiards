package com.minamikune.billiards.game

import com.minamikune.billiards.physics.BallGroup
import com.minamikune.billiards.physics.BallState
import com.minamikune.billiards.physics.PhysicsEvent
import com.minamikune.billiards.physics.PhysicsWorld
import com.minamikune.billiards.physics.Rack
import com.minamikune.billiards.physics.Strike
import com.minamikune.billiards.physics.TableSpec
import com.minamikune.billiards.physics.Vec2
import com.minamikune.billiards.physics.Vec3
import kotlin.math.abs
import kotlin.math.min

enum class GameMode { PRACTICE, VS_AI }

enum class SessionState { AIMING, BALL_IN_HAND, SIMULATING, AI_THINKING, GAME_OVER }

data class PlayerInfo(
    val name: String,
    val group: BallGroup? = null,
    val pocketed: Int = 0,
    val fouls: Int = 0,
)

/** A renderer/UI-friendly immutable view of a match. */
data class MatchSnapshot(
    val mode: GameMode,
    val state: SessionState,
    val balls: List<BallState>,
    val currentPlayer: Int,
    val players: List<PlayerInfo>,
    val ballInHand: Boolean,
    val message: String,
    val winner: Int?,
    val shots: Int,
    val aimDirection: Vec2,
    val power: Double,
    val sideSpin: Double,
    val verticalSpin: Double,
    val aiThinking: Boolean,
    val lastPocketed: Int?,
)

/**
 * Complete 8-ball match flow around [PhysicsWorld].
 *
 * The engine owns turn state, fouls, group assignment, AI scheduling, and practice mode.
 * It is deliberately free of Android imports so the rules are unit-testable on the JVM.
 */
class MatchEngine(
    val mode: GameMode = GameMode.VS_AI,
    private val ai: BilliardsAi = BilliardsAi(),
    private val startsWithBreak: Boolean = true,
) {
    val world = PhysicsWorld()
    var state: SessionState = SessionState.AIMING
        private set
    var currentPlayer: Int = 0
        private set
    var players: MutableList<PlayerInfo> = mutableListOf(
        PlayerInfo("You"),
        PlayerInfo("DeepSeek AI"),
    )
        private set
    var ballInHand: Boolean = false
        private set
    var message: String = if (mode == GameMode.PRACTICE) {
        "Practice · pot any ball"
    } else {
        "Break · open table"
    }
        private set
    var winner: Int? = null
        private set
    var shots: Int = 0
        private set
    var lastPocketed: Int? = null
        private set
    var aimDirection: Vec2 = Vec2.UNIT_X
        private set
    var power: Double = 0.55
        private set
    var sideSpin: Double = 0.0
        private set
    var verticalSpin: Double = 0.0
        private set

    private val feedback = ArrayList<PhysicsEvent>(16)
    private var aiTimer = 0.0
    private var turnShotPocketed = mutableListOf<Int>()
    private var firstContact: Int? = null
    private var cushionAfterContact = 0
    private var pocketAfterContact = false
    private var cuePocketed = false
    private var shotIsBreak = false
    /** True only when the shooter had already cleared their group before this stroke. */
    private var clearedAtShotStart = false
    private val breakRailBalls = HashSet<Int>(8)
    private var settleDelay = -1.0
    private var shotInFlight = false
    private val breakPlayers = listOf("You", "DeepSeek AI")

    init {
        resetInternal(keepMode = true)
        if (mode == GameMode.PRACTICE) {
            players = mutableListOf(PlayerInfo("Practice"))
        }
    }

    fun reset() {
        resetInternal(keepMode = true)
    }

    private fun resetInternal(keepMode: Boolean) {
        world.resetRack()
        currentPlayer = 0
        players = mutableListOf(PlayerInfo(breakPlayers[0]), PlayerInfo(breakPlayers[1]))
        if (mode == GameMode.PRACTICE) players = mutableListOf(PlayerInfo("Practice"))
        state = SessionState.AIMING
        ballInHand = false
        winner = null
        shots = 0
        lastPocketed = null
        message = if (mode == GameMode.PRACTICE) "Practice · pot any ball" else "Break · open table"
        aimDirection = Vec2.UNIT_X
        power = 0.55
        sideSpin = 0.0
        verticalSpin = 0.0
        shotInFlight = false
        shotIsBreak = startsWithBreak && mode == GameMode.VS_AI
        settleDelay = -1.0
        aiTimer = 0.0
        resetShotTracking()
    }

    fun setAim(direction: Vec2) {
        if (state != SessionState.AIMING) return
        val n = direction.normalized()
        if (n.lengthSquared() > 0.5) aimDirection = n
    }

    fun setPower(value: Double) {
        power = value.coerceIn(0.05, 1.0)
    }

    fun setSpin(side: Double, vertical: Double) {
        if (state != SessionState.AIMING) return
        sideSpin = side.coerceIn(-0.5, 0.5)
        verticalSpin = vertical.coerceIn(-0.5, 0.5)
    }

    /** Fires the cue. Returns false when the current state does not allow a shot. */
    fun strike(): Boolean {
        if (state != SessionState.AIMING || !world.cueBall.active) return false
        clearedAtShotStart = mode == GameMode.VS_AI && remainingGroup(currentPlayer) == 0
        val speed = 0.8 + power * 8.2
        world.strikeCue(Strike(aimDirection, speed, sideSpin, verticalSpin))
        feedback.add(PhysicsEvent.CueStruck(speed))
        shots++
        state = SessionState.SIMULATING
        shotInFlight = true
        shotIsBreak = shotIsBreak && shots == 1 && mode == GameMode.VS_AI
        settleDelay = -1.0
        return true
    }

    /** Places the cue ball during ball-in-hand; validates the spot. */
    fun placeCueBall(position: Vec2): Boolean {
        if (state != SessionState.BALL_IN_HAND && !ballInHand) return false
        if (!world.placeCueBall(position)) return false
        ballInHand = false
        state = SessionState.AIMING
        message = "Cue ball placed"
        return true
    }

    fun rerack() {
        reset()
    }

    /** Advances the match; [dt] is a real-time delta, the physics is stepped internally. */
    fun update(dt: Double) {
        if (state == SessionState.GAME_OVER) return
        if (state == SessionState.SIMULATING) {
            world.step(dt.coerceIn(1.0 / 960.0, 1.0 / 60.0))
            for (event in world.drainEvents()) {
                feedback.add(event)
                when (event) {
                is PhysicsEvent.BallHit -> {
                    if (event.firstBallId == 0 || event.secondBallId == 0) {
                        val other = if (event.firstBallId == 0) event.secondBallId else event.firstBallId
                        if (firstContact == null) firstContact = other
                    }
                }
                is PhysicsEvent.CushionHit -> {
                    if (firstContact != null) cushionAfterContact++
                    if (shotIsBreak && event.ballId != 0) breakRailBalls.add(event.ballId)
                }
                is PhysicsEvent.Pocketed -> {
                    if (event.ballId == 0) {
                        cuePocketed = true
                    } else {
                        turnShotPocketed.add(event.ballId)
                        lastPocketed = event.ballId
                        if (firstContact != null) pocketAfterContact = true
                    }
                }
                PhysicsEvent.Settled -> {
                    if (shotInFlight) settleDelay = 0.28
                }
                else -> Unit
                }
            }
            if (settleDelay >= 0.0) {
                settleDelay -= dt
                if (settleDelay <= 0.0) finishShot()
            }
        } else if (state == SessionState.AI_THINKING) {
            aiTimer -= dt
            if (aiTimer <= 0.0) runAiTurn()
        }
    }

    private fun finishShot() {
        shotInFlight = false
        settleDelay = -1.0
        if (mode == GameMode.PRACTICE) {
            finishPracticeShot()
            return
        }
        evaluateEightBallShot()
    }

    private fun finishPracticeShot() {
        if (cuePocketed) {
            world.cueBall.active = false
            if (autoPlaceCueBall(Rack.cuePosition())) {
                message = "Scratch · cue ball respotted"
            } else {
                message = "Scratch · no free spot"
            }
        } else if (lastPocketed != null) {
            message = "Pocketed #$lastPocketed · keep practising"
        } else {
            message = "Practice · pot any ball"
        }
        cuePocketed = false
        lastPocketed = null
        state = SessionState.AIMING
        resetShotTracking()
    }

    private fun evaluateEightBallShot() {
        val player = currentPlayer
        val eightPocketed = turnShotPocketed.contains(8)
        val wasBreak = shotIsBreak
        // On a break the table stays open. Any potted object ball (including a
        // re-spotted 8) entitles the breaker to continue shooting.
        val breakPotted = wasBreak && (turnShotPocketed.isNotEmpty() || eightPocketed)
        var foul = false
        var foulReason = ""

        if (cuePocketed) {
            foul = true
            foulReason = "Cue ball pocketed · ball in hand"
        } else if (firstContact == null) {
            foul = true
            foulReason = "No contact · ball in hand"
        } else if (!isLegalFirstContact(player, firstContact!!)) {
            foul = true
            foulReason = "Wrong ball first · ball in hand"
        } else if (wasBreak && breakRailBalls.size < 4 && turnShotPocketed.isEmpty()) {
            foul = true
            foulReason = "Illegal break · ball in hand"
        } else if (!pocketAfterContact && cushionAfterContact == 0) {
            foul = true
            foulReason = "No rail after contact · ball in hand"
        }

        if (eightPocketed && wasBreak) {
            respotEight()
            turnShotPocketed.remove(8)
            message = "8-ball on the break · re-spotted"
            // A scratch or an illegal break is still a foul; only the 8-ball is re-spotted.
        } else if (eightPocketed) {
            // The group must have been cleared before this stroke. Sinking the last
            // group ball and the 8 on the same stroke is an early 8-ball loss.
            val cleared = clearedAtShotStart
            if (cleared && !foul) {
                win(player, "8-ball pocketed · win")
            } else {
                win(1 - player, if (foul) "Foul while potting the 8" else "8-ball pocketed early")
            }
            return
        }

        if (!foul && !wasBreak) {
            assignGroupsIfNeeded(player)
        }

        players[player] = players[player].copy(pocketed = players[player].pocketed + turnShotPocketed.size)
        if (foul) {
            players[player] = players[player].copy(fouls = players[player].fouls + 1)
            message = foulReason
            feedback.add(PhysicsEvent.Foul)
        }

        if (wasBreak) shotIsBreak = false

        if (winner != null) return

        val ownPocketed = turnShotPocketed.any {
            it != 8 && players[player].group != null && TableSpec.groupOf(it) == players[player].group
        }
        val continueTurn = !foul && (if (wasBreak) breakPotted else ownPocketed)
        if (foul) {
            switchPlayer(ballInHand = true)
        } else if (continueTurn) {
            if (message.startsWith("Cue") || message.startsWith("No ") || message.startsWith("Wrong") ||
                message.startsWith("Illegal")
            ) message = "Nice pot · shoot again"
            if (mode == GameMode.VS_AI && currentPlayer == 1) {
                state = SessionState.AI_THINKING
                aiTimer = 0.9
                message = "DeepSeek AI is thinking…"
            } else {
                state = SessionState.AIMING
            }
            resetShotTracking()
        } else {
            if (turnShotPocketed.isNotEmpty() && !foul) message = "Potted · turn over"
            switchPlayer(ballInHand = false)
        }
    }

    private fun switchPlayer(ballInHand: Boolean) {
        currentPlayer = 1 - currentPlayer
        this.ballInHand = ballInHand
        if (ballInHand) {
            world.cueBall.active = false
            state = SessionState.BALL_IN_HAND
        }
        resetShotTracking()
        if (mode == GameMode.VS_AI && currentPlayer == 1) {
            state = SessionState.AI_THINKING
            aiTimer = 0.9
            message = "DeepSeek AI is thinking…"
        } else if (ballInHand) {
            state = SessionState.BALL_IN_HAND
            message = "Ball in hand · drag the cue ball"
        } else {
            state = SessionState.AIMING
            if (message.startsWith("No ") || message.startsWith("Wrong") ||
                message.startsWith("Illegal") || message.startsWith("Cue")
            ) {
                // Keep the foul explanation visible.
            } else {
                message = "Your shot"
            }
        }
    }

    private fun assignGroupsIfNeeded(player: Int) {
        if (players[player].group != null) return
        val solids = turnShotPocketed.count { TableSpec.groupOf(it) == BallGroup.SOLIDS }
        val stripes = turnShotPocketed.count { TableSpec.groupOf(it) == BallGroup.STRIPES }
        when {
            solids > 0 && stripes == 0 -> players[player] = players[player].copy(group = BallGroup.SOLIDS)
            stripes > 0 && solids == 0 -> players[player] = players[player].copy(group = BallGroup.STRIPES)
        }
        val opponent = 1 - player
        val assigned = players[player].group ?: return
        players[opponent] = players[opponent].copy(
            group = when (assigned) {
                BallGroup.SOLIDS -> BallGroup.STRIPES
                BallGroup.STRIPES -> BallGroup.SOLIDS
                else -> null
            },
        )
        message = if (assigned == BallGroup.SOLIDS) "You are solids" else "You are stripes"
    }

    private fun isLegalFirstContact(player: Int, ballId: Int): Boolean {
        if (mode == GameMode.PRACTICE) return true
        if (ballId == 8) return remainingGroup(player) == 0
        val group = players[player].group ?: return ballId != 8
        return TableSpec.groupOf(ballId) == group
    }

    fun remainingGroup(player: Int): Int {
        val group = players[player].group ?: return -1
        return world.balls.count { it.active && TableSpec.groupOf(it.id) == group }
    }

    fun legalTargets(player: Int = currentPlayer): Set<Int> {
        if (mode == GameMode.PRACTICE) {
            return world.balls.filter { it.active && it.id != 0 && it.id != 8 }.map { it.id }.toSet()
        }
        val group = players.getOrNull(player)?.group
        return if (group == null) {
            world.balls.filter { it.active && it.id != 0 && it.id != 8 }.map { it.id }.toSet()
        } else if (remainingGroup(player) == 0) {
            world.balls.filter { it.active && it.id == 8 }.map { it.id }.toSet()
        } else {
            world.balls.filter { it.active && TableSpec.groupOf(it.id) == group }.map { it.id }.toSet()
        }
    }

    private fun win(player: Int, reason: String) {
        winner = player
        state = SessionState.GAME_OVER
        feedback.add(PhysicsEvent.MatchWon(player == 0))
        message = if (mode == GameMode.PRACTICE) reason else {
            if (player == 0) "You win · $reason" else "AI wins · $reason"
        }
    }

    private fun respotEight() {
        val eight = world.balls[8]
        eight.active = true
        eight.asleep = false
        eight.velocity = Vec2.ZERO
        eight.angularVelocity = Vec3.ZERO
        eight.pocketIndex = -1
        val base = Rack.positions()[8] ?: Vec2(TableSpec.HALF_LENGTH / 2.0, 0.0)
        var spot = base
        var step = 0.0
        while (step < 1.0) {
            if (world.balls.none { it.id != 8 && it.active && it.position.distanceTo(spot) < 2.1 * TableSpec.BALL_RADIUS }) break
            step += TableSpec.BALL_RADIUS
            spot = Vec2(base.x, base.y + if ((step / TableSpec.BALL_RADIUS).toInt() % 2 == 0) step else -step)
        }
        eight.position = spot
        eight.previousPosition = spot
    }

    private fun runAiTurn() {
        if (state != SessionState.AI_THINKING) return
        if (ballInHand) {
            val placement = ai.choosePlacement(world, legalTargets(1))
            if (autoPlaceCueBall(placement)) {
                ballInHand = false
            }
        }
        if (!world.cueBall.active) return
        val shot = ai.chooseShot(world, legalTargets(1), seed = shots * 7919L + 17L)
        aimDirection = shot.direction
        power = shot.power
        sideSpin = 0.0
        verticalSpin = shot.verticalSpin
        resetShotTracking()
        clearedAtShotStart = remainingGroup(1) == 0
        world.strikeCue(
            Strike(
                direction = shot.direction,
                speed = shot.speed,
                sideSpin = 0.0,
                verticalSpin = shot.verticalSpin,
            ),
        )
        feedback.add(PhysicsEvent.CueStruck(shot.speed))
        shots++
        state = SessionState.SIMULATING
        shotInFlight = true
        shotIsBreak = false
        settleDelay = -1.0
        message = "AI shoots"
    }

    /**
     * Places the cue ball at [preferred] when legal, otherwise searches a dense grid
     * inside the cushions. A scratch or an AI ball-in-hand must never leave the cue
     * inactive and the match unable to continue.
     */
    private fun autoPlaceCueBall(preferred: Vec2): Boolean {
        if (world.placeCueBall(preferred)) {
            world.cueBall.active = true
            return true
        }
        val r = TableSpec.BALL_RADIUS
        var x = -TableSpec.HALF_LENGTH + r * 2.2
        while (x <= TableSpec.HALF_LENGTH - r * 2.2) {
            var y = -TableSpec.HALF_WIDTH + r * 2.2
            while (y <= TableSpec.HALF_WIDTH - r * 2.2) {
                if (world.placeCueBall(Vec2(x, y))) {
                    world.cueBall.active = true
                    return true
                }
                y += r * 2.2
            }
            x += r * 2.2
        }
        return false
    }

    private fun resetShotTracking() {
        turnShotPocketed.clear()
        firstContact = null
        cushionAfterContact = 0
        pocketAfterContact = false
        cuePocketed = false
        clearedAtShotStart = false
        breakRailBalls.clear()
    }

    /** Drains one-shot audio/haptic feedback. Safe to call from the game thread. */
    fun drainFeedback(): List<PhysicsEvent> {
        if (feedback.isEmpty()) return emptyList()
        val copy = ArrayList<PhysicsEvent>(feedback)
        feedback.clear()
        return copy
    }

    fun snapshot(): MatchSnapshot = MatchSnapshot(
        mode = mode,
        state = state,
        balls = world.snapshot(),
        currentPlayer = currentPlayer,
        players = players.toList(),
        ballInHand = ballInHand,
        message = message,
        winner = winner,
        shots = shots,
        aimDirection = aimDirection,
        power = power,
        sideSpin = sideSpin,
        verticalSpin = verticalSpin,
        aiThinking = state == SessionState.AI_THINKING,
        lastPocketed = lastPocketed,
    )

    fun restore(snapshot: MatchSnapshot) {
        world.restore(snapshot.balls)
        state = snapshot.state
        currentPlayer = snapshot.currentPlayer
        players = snapshot.players.toMutableList()
        ballInHand = snapshot.ballInHand
        message = snapshot.message
        winner = snapshot.winner
        shots = snapshot.shots
        aimDirection = snapshot.aimDirection
        power = snapshot.power
        sideSpin = snapshot.sideSpin
        verticalSpin = snapshot.verticalSpin
        lastPocketed = snapshot.lastPocketed
        shotInFlight = false
        settleDelay = -1.0
        shotIsBreak = false
        resetShotTracking()
    }
}

/** Geometry-only AI: finds legal pot lines and falls back to a safety hit. */
class BilliardsAi {
    data class AiShot(
        val direction: Vec2,
        val speed: Double,
        val power: Double,
        val verticalSpin: Double = 0.0,
    )

    fun choosePlacement(world: PhysicsWorld, targets: Set<Int>): Vec2 {
        val candidates = ArrayList<Vec2>()
        val head = Rack.cuePosition()
        candidates.add(head)
        var x = -0.9
        while (x <= 0.4) {
            var y = -0.45
            while (y <= 0.45) {
                candidates.add(Vec2(x, y))
                y += 0.15
            }
            x += 0.15
        }
        val target = world.balls.filter { it.active && it.id in targets }
        return candidates
            .filter { isValid(world, it, targets) }
            .maxByOrNull { spot ->
                target.maxOfOrNull { ball -> -ball.position.distanceTo(spot) } ?: -1.0
            } ?: head
    }

    private fun isValid(world: PhysicsWorld, spot: Vec2, targets: Set<Int>): Boolean {
        if (!com.minamikune.billiards.physics.isValidCuePlacement(spot)) return false
        for (ball in world.balls) {
            if (ball.id == 0 || !ball.active) continue
            if (ball.position.distanceTo(spot) < 2.05 * TableSpec.BALL_RADIUS) return false
        }
        return targets.isNotEmpty()
    }

    fun chooseShot(world: PhysicsWorld, targets: Set<Int>, seed: Long = 0L): AiShot {
        val cue = world.cueBall
        val active = world.balls.filter { it.active && it.id != 0 }
        val legal = active.filter { it.id in targets }
        var best: AiShot? = null
        var bestScore = -1e9

        for (ball in legal) {
            for (pocket in TableSpec.pockets) {
                val toPocket = pocket.center - ball.position
                val pocketDist = toPocket.length()
                if (pocketDist < 1e-6) continue
                val pocketDir = toPocket / pocketDist
                val ghost = ball.position - pocketDir * (2.0 * TableSpec.BALL_RADIUS)
                val toGhost = ghost - cue.position
                val cueDist = toGhost.length()
                if (cueDist < 1e-6) continue
                val direction = toGhost / cueDist
                val cut = direction.dot(pocketDir)
                if (cut < 0.30) continue
                if (!clearPath(cue.position, ghost, active, ball.id)) continue
                if (!clearPath(ball.position, pocket.center, active, ball.id)) continue
                val anglePenalty = (1.0 - cut) * 2.4
                val score = 10.0 - cueDist * 0.8 - pocketDist * 0.9 - anglePenalty
                if (score > bestScore) {
                    bestScore = score
                    val speed = (2.0 + cueDist * 1.1 + pocketDist * 1.4 + anglePenalty * 1.2)
                        .coerceIn(1.8, 8.0)
                    best = AiShot(direction, speed, (speed / 9.0).coerceIn(0.2, 0.95))
                }
            }
        }

        val chosen = best ?: run {
            val target = legal.minByOrNull { it.position.distanceTo(cue.position) }
                ?: active.minByOrNull { it.position.distanceTo(cue.position) }
            if (target == null) {
                AiShot(Vec2.UNIT_X, 2.5, 0.28)
            } else {
                val direction = (target.position - cue.position).normalized()
                AiShot(direction, 2.6, 0.29, verticalSpin = 0.06)
            }
        }

        // Difficulty-aware, deterministic aim error (never enough to miss the ball entirely).
        val rng = java.util.Random(seed xor 0x5DEECE66DL)
        val error = if (best != null) (rng.nextDouble() - 0.5) * 0.018 else (rng.nextDouble() - 0.5) * 0.03
        val rotated = rotate(chosen.direction, error)
        return chosen.copy(direction = rotated)
    }

    private fun rotate(v: Vec2, angle: Double): Vec2 {
        val c = kotlin.math.cos(angle)
        val s = kotlin.math.sin(angle)
        return Vec2(v.x * c - v.y * s, v.x * s + v.y * c)
    }

    private fun clearPath(from: Vec2, to: Vec2, balls: List<com.minamikune.billiards.physics.Ball>, skip: Int): Boolean {
        val d = to - from
        val len = d.length()
        if (len < 1e-6) return true
        val dir = d / len
        for (ball in balls) {
            if (ball.id == skip || !ball.active) continue
            val rel = ball.position - from
            val t = rel.dot(dir)
            if (t < 0.0 || t > len) continue
            val perp = abs(rel.x * dir.y - rel.y * dir.x)
            if (perp < 2.0 * TableSpec.BALL_RADIUS * 0.98) return false
        }
        return true
    }
}

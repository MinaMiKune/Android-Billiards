package com.minamikune.billiards.game

import com.minamikune.billiards.physics.PhysicsEvent
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * Dedicated simulation thread. A fixed accumulator calls MatchEngine.update(1/480)
 * independent of display refresh rate. UI writes are serialised onto this thread.
 */
class GameLoop(
    private val engine: MatchEngine,
    private val onSnapshot: (MatchSnapshot) -> Unit,
    private val onFeedback: (List<PhysicsEvent>) -> Unit,
) {
    @Volatile var paused = false
        private set
    @Volatile var running = true
        private set
    @Volatile var latest: MatchSnapshot? = null
        private set

    private val commands = ConcurrentLinkedQueue<(MatchEngine) -> Unit>()
    private val stopFlag = AtomicBoolean(false)
    private var thread: Thread? = null
    @Volatile private var boostUntil = 0L
    private var lastNanos = 0L
    private var accumulator = 0.0
    private var snapshotClock = 0.0
    private var lastPublishedState: SessionState? = null

    fun start() {
        if (thread != null) return
        running = true
        stopFlag.set(false)
        val worker = Thread({ loop(Thread.currentThread()) }, "billiards-sim")
        worker.priority = Thread.NORM_PRIORITY + 1
        thread = worker
        worker.start()
    }

    fun stop() {
        running = false
        stopFlag.set(true)
        val worker = thread
        thread = null
        // Interrupt a sleeping worker, then give the current fixed step time to finish.
        // The identity check in loop() prevents a restarted loop from reviving this one.
        worker?.interrupt()
        try {
            worker?.join(400)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    fun pause() {
        paused = true
        lastNanos = 0L
    }

    fun resume() {
        lastNanos = 0L
        paused = false
    }

    fun submit(command: (MatchEngine) -> Unit) {
        commands.add(command)
    }

    /** Temporarily accelerates the remaining slow tail without changing physics. */
    fun boost(seconds: Double = 3.0) {
        boostUntil = System.nanoTime() + (seconds * 1_000_000_000L).toLong()
    }

    fun publishNow() {
        val snap = synchronized(engine) { engine.snapshot() }
        latest = snap
        onSnapshot(snap)
    }

    private fun loop(self: Thread) {
        while (running && thread === self && !stopFlag.get()) {
            val now = System.nanoTime()
            if (paused) {
                lastNanos = now
                sleep(16)
                continue
            }
            if (lastNanos == 0L) lastNanos = now
            val dt = min(0.05, max(0.0, (now - lastNanos) / 1_000_000_000.0))
            lastNanos = now

            var scale = 1.0
            if (System.nanoTime() < boostUntil) scale = 4.0
            val state = synchronized(engine) { engine.state }
            if (state == SessionState.SIMULATING && maxSpeed() < 0.30) scale = max(scale, 2.6)

            accumulator += dt * scale
            var steps = 0
            while (accumulator >= STEP && steps < 140) {
                var cmd = commands.poll()
                while (cmd != null) {
                    try {
                        synchronized(engine) { cmd(engine) }
                    } catch (_: Throwable) {
                        // A malformed UI command must not kill the simulation thread.
                    }
                    cmd = commands.poll()
                }
                try {
                    synchronized(engine) { engine.update(STEP) }
                } catch (_: Throwable) {
                    // The engine already clamps states; stop this step rather than spin.
                }
                val feedback = synchronized(engine) { engine.drainFeedback() }
                if (feedback.isNotEmpty()) {
                    try {
                        onFeedback(feedback)
                    } catch (_: Throwable) {
                        Unit
                    }
                }
                accumulator -= STEP
                steps++
            }
            if (steps >= 140) accumulator = 0.0

            snapshotClock += dt
            if (snapshotClock >= 0.075) {
                snapshotClock = 0.0
                val snap = synchronized(engine) { engine.snapshot() }
                latest = snap
                if (snap.state != lastPublishedState || snap.state == SessionState.SIMULATING) {
                    lastPublishedState = snap.state
                    try {
                        onSnapshot(snap)
                    } catch (_: Throwable) {
                        Unit
                    }
                } else {
                    // Publish aiming-state UI changes such as power/spin at a lower rate.
                    try {
                        onSnapshot(snap)
                    } catch (_: Throwable) {
                        Unit
                    }
                }
            }
            sleep(2)
        }
    }

    private fun maxSpeed(): Double {
        synchronized(engine) {
            var m = 0.0
            for (b in engine.world.balls) {
                if (!b.active) continue
                if (b.velocity.length() > m) m = b.velocity.length()
            }
            return m
        }
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            running = false
        }
    }

    companion object {
        const val STEP = 1.0 / 480.0
    }
}

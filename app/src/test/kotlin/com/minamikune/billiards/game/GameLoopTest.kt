package com.minamikune.billiards.game

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameLoopTest {
    @Test
    fun publishesSnapshotsAndStopsCleanly() {
        val engine = MatchEngine(GameMode.PRACTICE)
        val first = CountDownLatch(1)
        val loop = GameLoop(engine, { first.countDown() }, {})
        loop.start()
        assertTrue("simulation did not publish a snapshot", first.await(2, TimeUnit.SECONDS))
        assertNotNull(loop.latest)
        loop.stop()
        assertFalse(loop.running)
        loop.stop() // idempotent
    }

    @Test
    fun submittedCommandIsAppliedOnSimulationThread() {
        val engine = MatchEngine(GameMode.PRACTICE)
        val ready = CountDownLatch(1)
        val loop = GameLoop(engine, { ready.countDown() }, {})
        loop.start()
        assertTrue(ready.await(2, TimeUnit.SECONDS))
        loop.submit { it.setPower(0.83) }
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            if (loop.latest?.power == 0.83) break
            Thread.sleep(20)
        }
        loop.stop()
        assertTrue(engine.power == 0.83)
    }
}

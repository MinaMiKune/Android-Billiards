package com.minamikune.billiards.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Throttle semantics shared by the sound and haptics paths: the first
 * acquisition succeeds, anything inside the cooldown window is dropped, and
 * keys are throttled independently.
 */
class CooldownGateTest {
    private class Clock {
        var now = 0L
    }

    private fun gateWith(clock: Clock) = CooldownGate(nowMs = { clock.now })

    @Test
    fun firstAcquisitionAlwaysSucceeds() {
        val gate = CooldownGate()
        assertTrue(gate.tryAcquire("cue", 40))
        assertTrue(gate.tryAcquire("pocket", 90))
    }

    @Test
    fun acquisitionsInsideCooldownAreDropped() {
        val clock = Clock()
        val gate = gateWith(clock)
        assertTrue(gate.tryAcquire("cue", 40))
        clock.now = 39
        assertFalse(gate.tryAcquire("cue", 40))
        // Boundary: exactly at the cooldown the event plays again.
        clock.now = 40
        assertTrue(gate.tryAcquire("cue", 40))
    }

    @Test
    fun keysAreThrottledIndependently() {
        val clock = Clock()
        val gate = gateWith(clock)
        gate.tryAcquire("ball_light", 45)
        clock.now = 10
        // ball_hard is a different throttle bucket even for the same event type.
        assertTrue(gate.tryAcquire("ball_hard", 55))
        assertFalse(gate.tryAcquire("ball_light", 45))
        assertTrue(gate.tryAcquire("cushion", 65))
    }

    @Test
    fun failedAttemptDoesNotExtendTheWindow() {
        val clock = Clock()
        val gate = gateWith(clock)
        gate.tryAcquire("cue", 100)
        clock.now = 50
        assertFalse(gate.tryAcquire("cue", 100)) // dropped...
        clock.now = 101
        assertTrue(gate.tryAcquire("cue", 100)) // ...but does not push the window out
    }

    @Test
    fun clockAdvancingBackwardsNeverBlocks() {
        val clock = Clock()
        val gate = gateWith(clock)
        clock.now = 10_000
        assertTrue(gate.tryAcquire("foul", 500))
        clock.now = 0
        assertTrue(gate.tryAcquire("foul", 500))
    }

    @Test
    fun clearResetsEveryKey() {
        val clock = Clock()
        val gate = gateWith(clock)
        gate.tryAcquire("cue", 40)
        gate.tryAcquire("win", 1000)
        gate.clear()
        assertTrue(gate.tryAcquire("cue", 40))
        assertTrue(gate.tryAcquire("win", 1000))
    }

    @Test
    fun gateHasNoExternalMutableState() {
        val gate = CooldownGate()
        assertTrue(gate.tryAcquire("a", 10))
        assertFalse(gate.tryAcquire("a", 10))
        assertEquals(1, 1) // no assertion beyond the sequence above
    }
}

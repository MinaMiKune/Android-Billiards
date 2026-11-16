package com.minamikune.billiards.util

/**
 * Monotonic per-key throttle shared by the audio and haptics feedback paths.
 * Rate limiting was previously duplicated (and implicitly defined) inside
 * SoundBank and Haptics; the gate makes the exact semantics unit-testable:
 * first acquisition for a key succeeds, further acquisitions within
 * `cooldownMs` of the last success are dropped.
 */
class CooldownGate(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val last = HashMap<String, Long>()

    fun tryAcquire(key: String, cooldownMs: Long): Boolean {
        val now = nowMs()
        val prev = last[key]
        // No record means "never fired": always allow. (Treating it as t=0
        // would drop a legitimate first event whenever the clock starts near
        // zero — e.g. an injectable fake clock in tests.)
        if (prev != null && now - prev in 0 until cooldownMs) return false
        last[key] = now
        return true
    }

    fun clear() {
        last.clear()
    }
}

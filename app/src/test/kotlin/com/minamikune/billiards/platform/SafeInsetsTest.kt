package com.minamikune.billiards.platform

import org.junit.Assert.assertEquals
import org.junit.Test

class SafeInsetsTest {

    @Test
    fun `null cutout passes system bars through unchanged`() {
        val s = SafeInsets.LTRB(0, 63, 0, 126)
        assertEquals(s, SafeInsets.merge(s, null))
    }

    @Test
    fun `zero system bar insets in immersive mode are filled by cutout`() {
        // Real-device regression: hidden system bars report 0 while the notch
        // still occupies the top edge; the merged top inset must be the
        // cutout's safe inset so the HUD scoreboard stays below the notch.
        val merged = SafeInsets.merge(
            SafeInsets.LTRB(0, 0, 0, 0),
            SafeInsets.LTRB(0, 89, 0, 0),
        )
        assertEquals(SafeInsets.LTRB(0, 89, 0, 0), merged)
    }

    @Test
    fun `merge takes the component-wise maximum`() {
        val merged = SafeInsets.merge(
            SafeInsets.LTRB(12, 63, 8, 126),
            SafeInsets.LTRB(0, 89, 24, 0),
        )
        assertEquals(SafeInsets.LTRB(12, 89, 24, 126), merged)
    }

    @Test
    fun `landscape cutout on left edge is preserved`() {
        val merged = SafeInsets.merge(
            SafeInsets.LTRB(0, 0, 0, 0),
            SafeInsets.LTRB(96, 0, 0, 0),
        )
        assertEquals(96, merged.left)
        assertEquals(0, merged.top)
    }
}

package com.minamikune.billiards.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.minamikune.billiards.game.GameMode
import com.minamikune.billiards.game.MatchEngine
import com.minamikune.billiards.physics.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SaveStoreTest {
    @Test
    fun bundleCodecRoundTripsACompleteSnapshot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SaveStore(context)
        val original = MatchEngine(GameMode.VS_AI, startsWithBreak = true).snapshot().copy(
            power = 0.77,
            sideSpin = 0.21,
            verticalSpin = -0.13,
            message = "测试中文",
            aimDirection = Vec2(0.6, -0.8),
        )

        val encoded = store.encode(original)
        val decoded = store.decode(encoded)

        assertNotNull(decoded)
        requireNotNull(decoded)
        assertEquals(original.mode, decoded.mode)
        assertEquals(original.state, decoded.state)
        assertEquals(original.balls.size, decoded.balls.size)
        assertEquals(0.77, decoded.power, 0.00001)
        assertEquals(0.21, decoded.sideSpin, 0.00001)
        assertEquals(-0.13, decoded.verticalSpin, 0.00001)
        assertEquals("测试中文", decoded.message)
        assertEquals(original.currentPlayer, decoded.currentPlayer)
        assertEquals(original.players, decoded.players)
    }

    @Test
    fun malformedPayloadReturnsNullInsteadOfThrowing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SaveStore(context)
        assertEquals(null, store.decode("{"))
        assertEquals(null, store.decode("{\"version\":99}"))
    }
}

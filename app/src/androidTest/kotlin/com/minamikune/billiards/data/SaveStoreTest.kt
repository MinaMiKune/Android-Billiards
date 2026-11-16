package com.minamikune.billiards.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.minamikune.billiards.game.GameMode
import com.minamikune.billiards.game.MatchEngine
import com.minamikune.billiards.physics.Vec2
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    /** Roadmap §7.2 corruption matrix: every mutation must decode to null. */
    @Test
    fun corruptionMatrixReturnsNull() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SaveStore(context)
        val baseline = JSONObject(store.encode(MatchEngine(GameMode.VS_AI, startsWithBreak = true).snapshot()))

        // Missing / wrong version.
        assertNull(store.decode(baseline.copy().apply { remove("version") }.toString()))
        assertNull(store.decode(baseline.copy().put("version", 2).toString()))

        // Missing balls / empty balls / wrong ball count.
        assertNull(store.decode(baseline.copy().apply { remove("balls") }.toString()))
        assertNull(store.decode(baseline.copy().put("balls", JSONArray()).toString()))
        assertNull(store.decode(baseline.copy().apply { getJSONArray("balls").remove(15) }.toString()))

        // Illegal enum values.
        assertNull(store.decode(baseline.copy().put("mode", "NOT_A_MODE").toString()))
        assertNull(store.decode(baseline.copy().put("state", "NOT_A_STATE").toString()))

        // Ball id integrity: out-of-range id and duplicate ids.
        assertNull(store.decode(baseline.copy().apply { getJSONArray("balls").getJSONObject(3).put("id", 16) }.toString()))
        assertNull(store.decode(baseline.copy().apply { getJSONArray("balls").getJSONObject(4).put("id", 0) }.toString()))

        // Non-finite numbers (org.json coerces the string "NaN" on getDouble).
        assertNull(
            store.decode(
                baseline.copy().apply { getJSONArray("balls").getJSONObject(0).getJSONArray("pos").put(0, "NaN") }.toString(),
            ),
        )
        assertNull(
            store.decode(
                baseline.copy().apply { getJSONArray("balls").getJSONObject(1).getJSONArray("vel").put(1, "Infinity") }.toString(),
            ),
        )

        // Structural fields that must be present and sane.
        assertNull(store.decode(baseline.copy().apply { remove("aim") }.toString()))
        assertNull(store.decode(baseline.copy().apply { getJSONArray("aim").put(0, "NaN") }.toString()))
        assertNull(store.decode(baseline.copy().put("players", JSONArray()).toString()))
    }

    /** Cosmetic scalars survive a missing/non-finite value by falling back. */
    @Test
    fun cosmeticScalarsFallBackInsteadOfRejecting() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SaveStore(context)
        val baseline = JSONObject(store.encode(MatchEngine(GameMode.PRACTICE, startsWithBreak = false).snapshot()))

        val decoded = store.decode(baseline.copy().put("power", "NaN").toString())
        assertNotNull(decoded)
        requireNotNull(decoded)
        assertEquals(0.55, decoded.power, 0.00001)

        val decoded2 = store.decode(baseline.copy().put("current", 99).toString())
        assertNotNull(decoded2)
        requireNotNull(decoded2)
        assertEquals(decoded2.players.size - 1, decoded2.currentPlayer)
    }

    private fun JSONObject.copy(): JSONObject = JSONObject(this.toString())
}

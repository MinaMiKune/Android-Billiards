package com.minamikune.billiards.ui

import com.minamikune.billiards.game.SessionState
import com.minamikune.billiards.physics.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gesture semantics of the HUD touch layer: pointer ownership, release
 * behaviour, and the three touch defects fixed in this stage.
 */
class HudTouchControllerTest {
    private val layout = computeHudLayout(
        w = 1080,
        h = 2400,
        safeL = 0,
        safeT = 60,
        safeR = 0,
        safeB = 100,
        density = 2f,
    )
    private var context = aiming()
    private var power = 0.55f
    private val aims = ArrayList<Vec2>()

    private fun make() = HudTouchController(
        layout = { layout },
        context = { context },
        powerAnchor = { power },
        aimMapper = { x, y ->
            val p = Vec2(x / 100.0, y / 100.0)
            aims.add(p)
            p
        },
    )

    private fun aiming() = HudTouchContext(SessionState.AIMING, practice = true, currentPlayer = 0)

    private fun assertPower(expected: Double, effect: HudEffect, delta: Double = 1e-5) {
        val p = effect as HudEffect.PowerChanged
        org.junit.Assert.assertEquals(expected, p.value, delta)
    }

    private fun assertPowers(expected: Double, effects: List<HudEffect>, delta: Double = 1e-5) {
        assertEquals(1, effects.size)
        assertPower(expected, effects[0], delta)
    }

    private fun bar(l: HudLayout, frac: Float): Float = l.powerRect.bottom - l.powerRect.height * frac

    // ------------------------------------------------------------ hit order

    @Test
    fun pauseHitRegionTakesPriorityOverEverything() {
        val c = make()
        val effects = c.onDown(1, layout.pauseRect.centerX, layout.pauseRect.centerY)
        assertEquals(emptyList<HudEffect>(), effects)
        assertTrue(c.isPressed(HudPress.PAUSE))
        assertEquals(listOf(HudEffect.Pause), c.onUp(1, 100f, 100f))
    }

    @Test
    fun pauseFiresOnReleaseEvenWhenFingerLiftsElsewhere() {
        val c = make()
        c.onDown(1, layout.pauseRect.centerX, layout.pauseRect.centerY)
        assertEquals(listOf(HudEffect.Pause), c.onUp(1, 0f, 0f))
    }

    @Test
    fun shootRequiresAimingHumanTurn() {
        val c = make()
        context = HudTouchContext(SessionState.SIMULATING, practice = true, currentPlayer = 0)
        assertEquals(emptyList<HudEffect>(), c.onDown(1, layout.shootCenterX, layout.shootCenterY))
        context = HudTouchContext(SessionState.AIMING, practice = false, currentPlayer = 1)
        // AI turn: the shoot button is inert, and the tap falls through to
        // the table like every other non-control touch (as before).
        val fx = c.onDown(1, layout.shootCenterX, layout.shootCenterY)
        assertEquals(1, fx.size)
        assertTrue(fx[0] is HudEffect.AimAt)
    }

    @Test
    fun shootUsesLatestSnapshotAtRelease() {
        val c = make()
        context = HudTouchContext(SessionState.AIMING, practice = true, currentPlayer = 0)
        assertEquals(listOf(HudEffect.UiTap), c.onDown(1, layout.shootCenterX, layout.shootCenterY))
        // Mid-press the session switches to simulating (e.g. frame race).
        context = HudTouchContext(SessionState.SIMULATING, practice = true, currentPlayer = 0)
        assertEquals(emptyList<HudEffect>(), c.onUp(1, layout.shootCenterX, layout.shootCenterY))
    }

    @Test
    fun aimDragIgnoredInTopScoreboardGuardBand() {
        val c = make()
        assertEquals(emptyList<HudEffect>(), c.onDown(1, 540f, layout.aimTopGuard - 1f))
        c.onDown(2, 540f, layout.aimTopGuard + 40f)
        assertEquals(1, aims.size)
    }

    @Test
    fun fastForwardOnlyAvailableWhileSimulating() {
        val c = make()
        // Outside simulating the fast-forward slot is not a control: a tap
        // there starts an aim drag, exactly like the original handler.
        val fx = c.onDown(1, layout.ffRect.centerX, layout.ffRect.centerY)
        assertEquals(1, fx.size)
        assertTrue(fx[0] is HudEffect.AimAt)
        val ff = make()
        context = HudTouchContext(SessionState.SIMULATING, practice = true, currentPlayer = 0)
        ff.onDown(1, layout.ffRect.centerX, layout.ffRect.centerY)
        assertTrue(ff.isPressed(HudPress.FAST_FORWARD))
        assertEquals(listOf(HudEffect.FastForward), ff.onUp(1, layout.ffRect.centerX, layout.ffRect.centerY))
    }

    // --------------------------------------------- pointer ownership (fix 1)

    @Test
    fun releasingOneFingerDoesNotDestroyTheOtherFingerGesture() {
        val c = make()
        // Finger A owns the aim drag, finger B owns the power drag.
        c.onDown(1, 500f, 1200f)
        c.onDown(2, layout.powerRect.centerX, bar(layout, 0.8f))
        assertTrue(c.draggingAim)
        assertTrue(c.draggingPower)
        // B lifts: the power drag simply ends (no power event on release),
        // and A's aim drag must survive.
        val up = c.onUp(2, layout.powerRect.centerX, bar(layout, 0.3f))
        assertEquals(emptyList<HudEffect>(), up)
        assertFalse(c.draggingPower)
        assertTrue("aim gesture must survive another pointer's release", c.draggingAim)
        val aim = c.onMove(1, 600f, 1300f)
        assertEquals(1, aim.size)
    }

    @Test
    fun secondaryPointerCannotHijackAnOwnedControl() {
        val c = make()
        c.onDown(1, layout.powerRect.centerX, bar(layout, 0.7f))
        // Second finger on the bar is ignored: ownership stays with pointer 1.
        assertEquals(emptyList<HudEffect>(), c.onDown(2, layout.powerRect.centerX, bar(layout, 0.2f)))
        val move = c.onMove(2, layout.powerRect.centerX, bar(layout, 0.2f))
        assertEquals(emptyList<HudEffect>(), move)
        // Anchor was 0.55, grab at 0.7 → relative offset -0.15.
        val move1 = c.onMove(1, layout.powerRect.centerX, bar(layout, 0.6f))
        assertPowers(0.45, move1)
    }

    @Test
    fun cancelResetsAllPointersAndPresses() {
        val c = make()
        c.onDown(1, layout.pauseRect.centerX, layout.pauseRect.centerY)
        c.onDown(2, 500f, 1200f)
        c.onCancel()
        assertFalse(c.isPressed(HudPress.PAUSE))
        assertFalse(c.draggingAim)
        assertEquals(emptyList<HudEffect>(), c.onUp(1, 500f, 1200f))
        assertEquals(emptyList<HudEffect>(), c.onUp(2, 500f, 1200f))
    }

    // ------------------------------------- power slider relative drag (fix 2)

    @Test
    fun grabbingBarAwayFromKnobDoesNotSnapPower() {
        val c = make()
        power = 0.8f
        // Grab near the BOTTOM of the bar (raw fraction ≈ 0.2) while power is 0.8.
        val effects = c.onDown(1, layout.powerRect.centerX, bar(layout, 0.2f))
        assertEquals(emptyList<HudEffect>(), effects)
        // First move emits the current power, not the raw grab position.
        val move = c.onMove(1, layout.powerRect.centerX, bar(layout, 0.35f))
        assertPowers(0.95, move)
    }

    @Test
    fun powerDragClampsToSliderRange() {
        val c = make()
        c.onDown(1, layout.powerRect.centerX, bar(layout, 0.5f))
        // Dragging below the bar bottoms out at the slider minimum...
        val below = c.onMove(1, layout.powerRect.centerX, layout.powerRect.bottom + 200f)
        assertPowers(0.05, below)
        // ...and above the bar tops out at full power.
        val above = c.onMove(1, layout.powerRect.centerX, layout.powerRect.top - 400f)
        assertPowers(1.0, above)
    }

    // ----------------------------------- shoot slides off button (fix 3)

    @Test
    fun shootDisarmsWhenFingerSlidesOffButton() {
        val c = make()
        c.onDown(1, layout.shootCenterX, layout.shootCenterY)
        assertTrue(c.isPressed(HudPress.SHOOT))
        // Slide far away: the press must disarm.
        c.onMove(1, 100f, 500f)
        assertFalse(c.isPressed(HudPress.SHOOT))
        assertEquals(emptyList<HudEffect>(), c.onUp(1, 100f, 500f))
    }

    @Test
    fun shootStillFiresWhenFingerStaysOnButton() {
        val c = make()
        c.onDown(1, layout.shootCenterX, layout.shootCenterY)
        c.onMove(1, layout.shootCenterX, layout.shootCenterY)
        assertEquals(listOf<HudEffect>(HudEffect.Shoot), c.onUp(1, layout.shootCenterX, layout.shootCenterY))
    }

    // -------------------------------------------------------- ball in hand

    @Test
    fun ballInHandPlacementUsesReleaseTimeState() {
        val c = make()
        context = HudTouchContext(SessionState.AIMING, practice = true, currentPlayer = 0)
        c.onDown(1, 500f, 1200f)
        // State flips to BALL_IN_HAND while the finger is down: placement,
        // not aiming, happens at release.
        context = HudTouchContext(SessionState.BALL_IN_HAND, practice = true, currentPlayer = 0)
        val up = c.onUp(1, 500f, 1200f)
        assertEquals(1, up.size)
        assertTrue(up[0] is HudEffect.PlaceCue)
    }

    @Test
    fun ballInHandDragOnlyPreviewsNoAimEvents() {
        val c = make()
        context = HudTouchContext(SessionState.BALL_IN_HAND, practice = true, currentPlayer = 0)
        c.onDown(1, 500f, 1200f)
        c.onMove(1, 550f, 1250f)
        assertEquals(0, aims.size)
        val up = c.onUp(1, 550f, 1250f)
        assertNotNull(up.single() as? HudEffect.PlaceCue)
    }

    // ---------------------------------------------------------------- spin

    @Test
    fun spinDragNormalizesAndScalesToEngineRange() {
        val c = make()
        val fx = c.onDown(1, layout.spinCenterX, layout.spinCenterY)
        assertEquals(1, fx.size)
        assertEquals(HudEffect.SpinChanged(0.0, 0.0), fx[0])
        val right = c.onMove(1, layout.spinCenterX + layout.spinRadius * 2f, layout.spinCenterY)
        assertEquals(HudEffect.SpinChanged(0.5, 0.0), right.single())
        val diag = c.onMove(
            1,
            layout.spinCenterX + layout.spinRadius,
            layout.spinCenterY - layout.spinRadius,
        )
        val spin = diag.single() as HudEffect.SpinChanged
        assertEquals(0.3535, spin.side, 0.001)
        assertEquals(0.3535, spin.vertical, 0.001)
    }

    @Test
    fun nullAimMappingSwallowsDragsGracefully() {
        val silent = HudTouchController(
            layout = { layout },
            context = { aiming() },
            powerAnchor = { power },
            aimMapper = { _, _ -> null },
        )
        assertEquals(emptyList<HudEffect>(), silent.onDown(1, 500f, 1200f))
        silent.onMove(1, 600f, 1300f)
        assertEquals(emptyList<HudEffect>(), silent.onUp(1, 600f, 1300f))
    }
}

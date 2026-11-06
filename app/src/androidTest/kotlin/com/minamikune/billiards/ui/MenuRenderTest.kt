package com.minamikune.billiards.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.minamikune.billiards.data.Prefs
import com.minamikune.billiards.game.GameMode
import com.minamikune.billiards.game.MatchEngine
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real Canvas layout/draw path at phone, landscape and tablet sizes.
 * The game has no XML layouts, so this catches the layout/array failures that a unit
 * test without an Android graphics stack cannot observe.
 */
@RunWith(AndroidJUnit4::class)
class MenuRenderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun menuScreensDrawAtRepresentativeSizes() {
        val menu = MenuView(context, Prefs(context))
        menu.setSafeInsets(32, 72, 32, 48)
        menu.showMain(saveAvailable = true)
        for ((w, h) in sizes()) draw(menu, w, h)

        val engine = MatchEngine(GameMode.VS_AI, startsWithBreak = false)
        menu.showPause(engine.snapshot())
        for ((w, h) in sizes()) draw(menu, w, h)

        menu.showSettings()
        for ((w, h) in sizes()) draw(menu, w, h)

        menu.showHelp()
        for ((w, h) in sizes()) draw(menu, w, h)

        menu.showResult(true, "Cleared the table", 21, 0)
        for ((w, h) in sizes()) draw(menu, w, h)
    }

    @Test
    fun hudDrawsInAimingAndSimulatingStates() {
        val engine = MatchEngine(GameMode.VS_AI)
        val hud = GameHudView(context)
        hud.setSafeInsets(32, 72, 32, 48)
        hud.setSnapshot(engine.snapshot())
        for ((w, h) in sizes()) draw(hud, w, h)
    }

    private fun sizes() = listOf(
        1080 to 2400, // tall phone
        2400 to 1080, // landscape phone
        1600 to 2560, // tablet
        900 to 1600,  // small foldable inner display
    )

    private fun draw(view: View, w: Int, h: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, w, h)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
        } finally {
            bitmap.recycle()
        }
    }
}

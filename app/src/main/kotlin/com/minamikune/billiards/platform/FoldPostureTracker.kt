package com.minamikune.billiards.platform

import android.app.Activity
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Bridges androidx.window [androidx.window.layout.WindowLayoutInfo] fold
 * events into the pure [FoldLayoutGuide] model consumed by the HUD, menus and
 * renderer.
 *
 * Non-foldable devices and FLAT postures emit [FoldPosture.FLAT], which every
 * consumer treats as "keep the baseline layout", so registering the tracker is
 * safe on all devices (phones simply always report FLAT).
 *
 * Events arrive on the main dispatcher; [onGuide] is invoked there. The latest
 * guide is also exposed via [currentGuide] so a freshly-created renderer can
 * be re-synchronised without waiting for the next fold event (stage 6 fix:
 * renderer recreation used to lose fold state).
 */
class FoldPostureTracker(
    private val activity: Activity,
    private val density: Float,
    private val onGuide: (FoldLayoutGuide) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var hinge: FoldHinge? = null
    private var windowW = 0
    private var windowH = 0

    /** Latest computed guide; null until the window size is known. */
    @Volatile
    var currentGuide: FoldLayoutGuide? = null
        private set

    fun start() {
        if (job != null || !scope.isActive) return
        job = scope.launch {
            runCatching {
                WindowInfoTracker.getOrCreate(activity)
                    .windowLayoutInfo(activity)
                    .collect { info ->
                        hinge = info.displayFeatures
                            .filterIsInstance<FoldingFeature>()
                            .firstOrNull()
                            ?.toFoldHinge()
                        emit()
                    }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        scope.cancel()
    }

    /** Content-view size in px; recomputes the guide so margins track the window. */
    fun onWindowSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || (w == windowW && h == windowH)) return
        windowW = w
        windowH = h
        emit()
    }

    private fun emit() {
        val w = windowW
        val h = windowH
        if (w <= 0 || h <= 0) return
        val guide = computeFoldLayout(
            windowW = w.toFloat(),
            windowH = h.toFloat(),
            hinge = hinge,
            marginPx = density * 16f,
        )
        currentGuide = guide
        onGuide(guide)
    }

    private fun FoldingFeature.toFoldHinge(): FoldHinge? {
        if (state != FoldingFeature.State.HALF_OPENED) return null
        return when (orientation) {
            FoldingFeature.Orientation.HORIZONTAL -> FoldHinge(
                FoldOrientation.HORIZONTAL,
                center = bounds.exactCenterY(),
                thickness = bounds.height().toFloat(),
                halfOpened = true,
            )
            FoldingFeature.Orientation.VERTICAL -> FoldHinge(
                FoldOrientation.VERTICAL,
                center = bounds.exactCenterX(),
                thickness = bounds.width().toFloat(),
                halfOpened = true,
            )
            else -> null
        }
    }
}

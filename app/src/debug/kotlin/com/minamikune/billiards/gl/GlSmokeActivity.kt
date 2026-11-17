package com.minamikune.billiards.gl

import android.app.Activity
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.widget.FrameLayout
import com.minamikune.billiards.game.GameMode
import com.minamikune.billiards.game.MatchEngine

/**
 * Hosts a real [TableRenderer] behind a plain activity so [GlSmokeTest] can
 * drive GL frames and the memory-trim path without the game UI overlay.
 */
class GlSmokeActivity : Activity() {
    lateinit var glView: GLSurfaceView
        private set
    lateinit var renderer: TableRenderer
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        renderer = TableRenderer(
            this,
            MatchEngine(GameMode.PRACTICE, startsWithBreak = false),
            highQuality = false,
        )
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        root.addView(glView)
        setContentView(root)
    }
}

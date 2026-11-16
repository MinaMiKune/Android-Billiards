package com.minamikune.billiards

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import com.minamikune.billiards.audio.SoundBank
import com.minamikune.billiards.data.Prefs
import com.minamikune.billiards.data.SaveStore
import com.minamikune.billiards.game.GameLoop
import com.minamikune.billiards.game.GameMode
import com.minamikune.billiards.game.MatchEngine
import com.minamikune.billiards.game.MatchSnapshot
import com.minamikune.billiards.game.SessionState
import com.minamikune.billiards.gl.TableRenderer
import com.minamikune.billiards.haptics.Haptics
import com.minamikune.billiards.platform.ActivityResources
import com.minamikune.billiards.platform.SafeInsets
import com.minamikune.billiards.physics.PhysicsEvent
import com.minamikune.billiards.physics.Vec2
import com.minamikune.billiards.ui.GameHudView
import com.minamikune.billiards.ui.MenuView
import com.minamikune.billiards.ui.SplashView

/**
 * Single activity host. The GL surface, simulation thread and Canvas HUD are kept
 * independent: simulation never runs on the UI thread and rendering never mutates
 * physics state.
 */
class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var hud: GameHudView
    private lateinit var menu: MenuView
    private lateinit var prefs: Prefs
    private lateinit var sound: SoundBank
    private lateinit var haptics: Haptics
    private lateinit var saves: SaveStore
    private lateinit var activityResources: ActivityResources
    private lateinit var splash: SplashView

    private var surface: GLSurfaceView? = null
    private var renderer: TableRenderer? = null
    private var demoEngine: MatchEngine? = null
    private var engine: MatchEngine? = null
    private var loop: GameLoop? = null
    private var loopGeneration = 0

    private var gameActive = false
    private var gameOverHandled = false
    private var currentMode = GameMode.PRACTICE
    private var stableSnapshot: MatchSnapshot? = null
    private var lastSavedKey = ""
    private var lastQuality = 0
    private var resumed = false
    private var splashActive = true

    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as BilliardsApplication).container
        prefs = container.prefs
        saves = container.saves
        activityResources = container.createActivityResources()
        sound = activityResources.sound
        sound.enabled = prefs.soundEnabled
        haptics = Haptics(this, prefs)
        lastQuality = prefs.quality

        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        setContentView(root)
        // The window's insets controller is only available after the decor view has
        // been installed; calling it before setContentView crashes on API 35.
        applyImmersive()

        hud = GameHudView(this, activityResources.fonts).apply {
            id = R.id.game_hud
            visibility = View.GONE
            screenToTable = { x, y -> renderer?.screenToTable(x, y) }
            onAimPoint = ::aimAt
            onPlaceCue = ::placeCue
            onShoot = ::shoot
            onPause = ::openPauseMenu
            onPower = { v -> loop?.submit { it.setPower(v) } }
            onSpin = { side, vertical -> loop?.submit { it.setSpin(side, vertical) } }
            onFastForward = {
                loop?.boost(2.8)
                sound.playUi()
            }
            onUiTap = { uiTap() }
        }
        menu = MenuView(this, prefs, activityResources.fonts).apply {
            id = R.id.game_menu
            visibility = View.VISIBLE
            onAction = ::onMenuAction
            onSettingsChanged = ::onSettingsChanged
            onUiTap = { uiTap() }
        }
        root.addView(hud, match())
        root.addView(menu, match())

        demoEngine = MatchEngine(GameMode.PRACTICE, startsWithBreak = false)
        installSurface(demoEngine!!)
        restoreState(savedInstanceState)

        root.setOnApplyWindowInsetsListener { _, insets ->
            val systemBars: SafeInsets.LTRB = if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars())
                SafeInsets.LTRB(i.left, i.top, i.right, i.bottom)
            } else {
                @Suppress("DEPRECATION")
                SafeInsets.LTRB(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom,
                )
            }
            // Real-device fix: in immersive mode the hidden system bars can
            // report zero insets (API 28–29 path) while the notch still
            // occupies the top edge, which pushed the HUD scoreboard under
            // the cutout. Always merge the cutout's own safe insets in.
            val cutout = if (Build.VERSION.SDK_INT >= 28) {
                insets.displayCutout?.let {
                    SafeInsets.LTRB(it.safeInsetLeft, it.safeInsetTop, it.safeInsetRight, it.safeInsetBottom)
                }
            } else {
                null
            }
            val safe = SafeInsets.merge(systemBars, cutout)
            hud.setSafeInsets(safe.left, safe.top, safe.right, safe.bottom)
            menu.setSafeInsets(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        root.requestApplyInsets()

        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            ) { handleBack() }
        }

        splash = SplashView(this, activityResources.config, activityResources.fonts).apply {
            id = R.id.game_splash
            onFinished = { finishSplash() }
        }
        root.addView(splash, match())
        splash.start()
    }

    /**
     * Rebuilds the visible screen after Activity recreation. A live match is restored
     * from the instance-state JSON first and reopened paused, never auto-running
     * behind the startup overlay. SharedPreferences remains the process-death fallback
     * offered as CONTINUE from the main menu.
     */
    private fun restoreState(state: Bundle?) {
        if (state == null) {
            menu.showMain(saves.hasSave())
            return
        }
        val restored = state.getString(KEY_SNAPSHOT)?.let { saves.decode(it) }
        if (restored != null) {
            startGame(restored.mode, restored, startPaused = true)
            return
        }
        menu.showMain(saves.hasSave())
        when (state.getString(KEY_SCREEN)) {
            "SETTINGS" -> menu.showSettings()
            "HELP" -> menu.showHelp()
        }
    }

    private fun finishSplash() {
        if (!splashActive || isFinishing || isDestroyed) return
        splashActive = false
        splash.onFinished = null
        root.removeView(splash)
        if (gameActive && menu.visibility != View.VISIBLE) {
            openPauseMenu()
        } else {
            menu.visibility = View.VISIBLE
        }
        updateKeepScreenOn()
        applyImmersive()
    }

    private fun match(): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )

    private fun highQuality(): Boolean {
        return when (prefs.quality) {
            1 -> true
            2 -> false
            else -> {
                val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
                if (am.isLowRamDevice) false else am.memoryClass >= activityResources.config.highQualityMemoryClass
            }
        }
    }

    private fun installSurface(game: MatchEngine) {
        surface?.let {
            try {
                it.onPause()
            } catch (_: Throwable) {
                Unit
            }
            root.removeView(it)
        }
        val gl = GLSurfaceView(this)
        gl.setEGLContextClientVersion(3)
        gl.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        gl.preserveEGLContextOnPause = true
        val r = TableRenderer(this, game, highQuality())
        renderer = r
        gl.setRenderer(r)
        gl.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        root.addView(gl, 0, match())
        surface = gl
        if (resumed) gl.onResume()
    }

    private fun recreateSurface() {
        installSurface(engine ?: demoEngine ?: return)
    }

    // ------------------------------------------------------------- game flow

    private fun startGame(mode: GameMode, restored: MatchSnapshot? = null, startPaused: Boolean = false) {
        loop?.stop()
        loop = null
        loopGeneration++
        if (restored == null) {
            currentMode = mode
            saves.clear()
        } else {
            currentMode = restored.mode
        }
        val e = if (restored != null) {
            MatchEngine(restored.mode, startsWithBreak = false).also { it.restore(restored) }
        } else {
            MatchEngine(mode, startsWithBreak = mode == GameMode.VS_AI)
        }
        engine = e
        gameActive = true
        gameOverHandled = false
        stableSnapshot = null
        lastSavedKey = ""
        if (renderer == null) installSurface(e) else renderer?.engine = e
        hud.setSnapshot(e.snapshot())
        hud.visibility = View.VISIBLE
        menu.visibility = View.GONE

        val generation = ++loopGeneration
        val gl = GameLoop(
            e,
            onSnapshot = { s ->
                runOnUiThread {
                    if (generation != loopGeneration || !gameActive) return@runOnUiThread
                    hud.setSnapshot(s)
                    if (isStable(s)) {
                        stableSnapshot = s
                        maybeSave(s)
                    }
                    if (s.state == SessionState.GAME_OVER) finishGame(s, generation)
                }
            },
            onFeedback = { events ->
                runOnUiThread {
                    if (generation != loopGeneration) return@runOnUiThread
                    for (event in events) {
                        sound.play(event)
                        haptics.play(event)
                    }
                }
            },
            snapshotIntervalSeconds = activityResources.config.snapshotIntervalSeconds,
        )
        loop = gl
        gl.start()
        if (startPaused) {
            gl.pause()
            val paused = currentSnapshot()
            if (paused != null) {
                hud.visibility = View.GONE
                menu.showPause(paused)
                menu.visibility = View.VISIBLE
            }
        } else {
            menu.visibility = View.GONE
        }
        updateKeepScreenOn()
        val initial = currentSnapshot()
        if (initial != null && isStable(initial)) {
            stableSnapshot = initial
            maybeSave(initial, force = true)
        }
    }

    private fun finishGame(s: MatchSnapshot, generation: Int) {
        if (gameOverHandled) return
        gameOverHandled = true
        gameActive = false
        loop?.pause()
        saves.clear()
        stableSnapshot = null
        hud.visibility = View.GONE
        updateKeepScreenOn()
        prefs.gamesPlayed = prefs.gamesPlayed + 1
        val humanWon = s.winner == 0
        if (s.mode == GameMode.VS_AI && humanWon) prefs.wins = prefs.wins + 1
        main.postDelayed({
            if (generation != loopGeneration || isFinishing) return@postDelayed
            menu.showResult(
                humanWon,
                s.message,
                s.shots,
                s.players.firstOrNull()?.fouls ?: 0,
            )
            menu.visibility = View.VISIBLE
        }, 950)
    }

    private fun currentSnapshot(): MatchSnapshot? {
        val e = engine ?: return null
        return synchronized(e) { e.snapshot() }
    }

    private fun isStable(s: MatchSnapshot): Boolean =
        s.state == SessionState.AIMING || s.state == SessionState.BALL_IN_HAND

    private fun maybeSave(s: MatchSnapshot, force: Boolean = false) {
        if (!gameActive || !isStable(s)) return
        val key = "${s.state}|${s.currentPlayer}|${s.shots}|${s.ballInHand}"
        if (!force && key == lastSavedKey) return
        lastSavedKey = key
        saves.save(s)
    }

    private fun saveBeforeLeaving() {
        if (!gameActive) return
        val s = currentSnapshot() ?: stableSnapshot ?: return
        if (isStable(s)) maybeSave(s, force = true) else stableSnapshot?.let { maybeSave(it, force = true) }
    }

    private fun openPauseMenu() {
        if (!gameActive) return
        if (menu.visibility == View.VISIBLE && menu.screen == MenuView.Screen.PAUSE) return
        loop?.pause()
        val s = currentSnapshot() ?: return
        if (isStable(s)) stableSnapshot = s else stableSnapshot?.let { maybeSave(it, force = true) }
        if (isStable(s)) maybeSave(s, force = true)
        hud.visibility = View.GONE
        menu.showPause(s)
        menu.visibility = View.VISIBLE
        updateKeepScreenOn()
        sound.playUi()
    }

    private fun resumeGame() {
        if (!gameActive) return
        menu.visibility = View.GONE
        hud.visibility = View.VISIBLE
        currentSnapshot()?.let { hud.setSnapshot(it) }
        loop?.resume()
        updateKeepScreenOn()
    }

    private fun returnToMain() {
        saveBeforeLeaving()
        loop?.stop()
        loop = null
        loopGeneration++
        gameActive = false
        gameOverHandled = false
        engine = null
        stableSnapshot = null
        demoEngine?.let { renderer?.engine = it }
        hud.visibility = View.GONE
        menu.showMain(saves.hasSave())
        menu.visibility = View.VISIBLE
        updateKeepScreenOn()
    }

    private fun onMenuAction(action: MenuView.Action) {
        when (action) {
            MenuView.Action.PRACTICE -> startGame(GameMode.PRACTICE)
            MenuView.Action.VS_AI -> startGame(GameMode.VS_AI)
            MenuView.Action.CONTINUE -> {
                val saved = saves.load()
                if (saved == null) {
                    saves.clear()
                    menu.showMain(false)
                } else {
                    val repaired = if (isStable(saved)) saved else saved.copy(state = SessionState.AIMING)
                    startGame(repaired.mode, repaired)
                }
            }
            MenuView.Action.RESUME -> resumeGame()
            MenuView.Action.RESTART -> {
                val mode = currentMode
                startGame(mode)
            }
            MenuView.Action.RETRY -> startGame(currentMode)
            MenuView.Action.MAIN_MENU -> returnToMain()
        }
    }

    private fun onSettingsChanged() {
        sound.enabled = prefs.soundEnabled
        if (prefs.quality != lastQuality) {
            lastQuality = prefs.quality
            recreateSurface()
        }
    }

    // ----------------------------------------------------------------- input

    private fun uiTap() {
        sound.playUi()
        haptics.uiTap(root)
    }

    private fun aimAt(point: Vec2) {
        val e = engine ?: return
        val cue = synchronized(e) { e.world.cueBall.position }
        val dx = point.x - cue.x
        val dy = point.y - cue.y
        if (dx * dx + dy * dy < 0.0004) return
        loop?.submit { it.setAim(Vec2(dx, dy)) }
    }

    private fun placeCue(point: Vec2) {
        loop?.submit {
            if (it.placeCueBall(point)) {
                runOnUiThread { sound.playUi() }
            }
        }
    }

    private fun shoot() {
        // The CueStruck feedback event drives both the cue sound and the impact
        // haptic, so do not fire an extra UI tick here.
        loop?.submit { it.strike() }
    }

    // -------------------------------------------------------------- lifecycle

    override fun onResume() {
        super.onResume()
        resumed = true
        surface?.onResume()
        applyImmersive()
        if (!splashActive && gameActive && menu.visibility != View.VISIBLE) {
            openPauseMenu()
        }
        updateKeepScreenOn()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        loop?.pause()
        saveBeforeLeaving()
        if (!splashActive && gameActive && menu.visibility != View.VISIBLE) {
            val s = currentSnapshot()
            if (s != null) {
                hud.visibility = View.GONE
                menu.showPause(s)
                menu.visibility = View.VISIBLE
            }
        }
        surface?.onPause()
        updateKeepScreenOn()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyImmersive()
        root.requestApplyInsets()
        hud.invalidate()
        menu.invalidate()
        if (splashActive) splash.invalidate()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    @SuppressLint("GestureBackNavigation")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // API 33+ is handled by the OnBackInvokedCallback registered in onCreate().
        if (Build.VERSION.SDK_INT >= 33) {
            // Predictive back is handled by the OnBackInvokedCallback registered in onCreate.
            return
        }
        handleBack()
    }

    private fun handleBack() {
        if (splashActive) {
            finishSplash()
            return
        }
        if (menu.visibility == View.VISIBLE) {
            if (!menu.handleBack()) finish()
        } else if (gameActive) {
            openPauseMenu()
        } else {
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val encoded = if (gameActive) {
            currentSnapshot()?.let { runCatching { saves.encode(it) }.getOrNull() }
        } else {
            null
        }
        if (encoded != null) outState.putString(KEY_SNAPSHOT, encoded)
        outState.putString(KEY_SCREEN, menu.screen.name)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        loop?.stop()
        loop = null
        loopGeneration++
        if (::splash.isInitialized) splash.onFinished = null
        if (::activityResources.isInitialized) activityResources.close()
        super.onDestroy()
    }

    /** Keep the display awake only while a live table is actually visible. */
    private fun updateKeepScreenOn() {
        if (!::menu.isInitialized) return
        val keepOn = resumed && gameActive && !splashActive && menu.visibility != View.VISIBLE
        if (keepOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ---------------------------------------------------------------- window

    @Suppress("DEPRECATION")
    private fun applyImmersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
    }

    private companion object {
        const val KEY_SNAPSHOT = "activity.match.snapshot"
        const val KEY_SCREEN = "activity.menu.screen"
    }
}

package com.minamikune.billiards.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.minamikune.billiards.data.Prefs
import com.minamikune.billiards.game.GameMode
import com.minamikune.billiards.game.MatchSnapshot
import com.minamikune.billiards.game.SessionState
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen menu, pause, result, settings and help UI. Drawn with Canvas so the
 * only UI dependency is the Android framework. All geometry is computed from the
 * current size and display insets, which keeps it usable on phones, foldables and
 * tablets in both orientations.
 */
class MenuView(context: Context, private val prefs: Prefs) : View(context) {
    @Suppress("unused")
    constructor(context: Context) : this(context, Prefs(context))


    enum class Screen { MAIN, SETTINGS, HELP, PAUSE, RESULT }
    enum class Action { PRACTICE, VS_AI, CONTINUE, RESUME, RESTART, MAIN_MENU, RETRY }

    var onAction: ((Action) -> Unit)? = null
    var onSettingsChanged: (() -> Unit)? = null
    var onUiTap: (() -> Unit)? = null

    var screen: Screen = Screen.MAIN
        private set
    private var returnScreen: Screen = Screen.MAIN
    private var hasSave = false
    private var pauseSnapshot: MatchSnapshot? = null
    private var resultWon = false
    private var resultMessage = ""
    private var resultShots = 0
    private var resultFouls = 0

    private var safeL = 0
    private var safeT = 0
    private var safeR = 0
    private var safeB = 0
    private val d = resources.displayMetrics.density
    private val scaled = resources.displayMetrics.scaledDensity

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        color = CREAM
    }
    private val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        color = CREAM
    }
    private val display = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        color = CREAM
    }

    private val buttons = mutableListOf<Btn>()
    private val panel = RectF()
    private val backRect = RectF()
    private val strengthBar = RectF()
    private var draggingStrength = false
    private var pressedId: String? = null
    private var animStart = 0L

    private data class Btn(
        val id: String,
        val label: String,
        val sub: String?,
        val primary: Boolean,
        val rect: RectF,
    )

    init {
        isClickable = true
        isFocusable = true
    }

    fun setSafeInsets(l: Int, t: Int, r: Int, b: Int) {
        safeL = l
        safeT = t
        safeR = r
        safeB = b
        rebuild()
        invalidate()
    }

    fun showMain(saveAvailable: Boolean) {
        hasSave = saveAvailable
        screen = Screen.MAIN
        returnScreen = Screen.MAIN
        animateIn()
    }

    fun showPause(snapshot: MatchSnapshot) {
        pauseSnapshot = snapshot
        screen = Screen.PAUSE
        returnScreen = Screen.PAUSE
        animateIn()
    }

    fun showSettings() {
        returnScreen = screen
        screen = Screen.SETTINGS
        animateIn()
    }

    fun showHelp() {
        returnScreen = screen
        screen = Screen.HELP
        animateIn()
    }

    fun showResult(won: Boolean, message: String, shots: Int, fouls: Int) {
        resultWon = won
        resultMessage = message
        resultShots = shots
        resultFouls = fouls
        screen = Screen.RESULT
        returnScreen = Screen.RESULT
        animateIn()
    }

    fun handleBack(): Boolean {
        return when (screen) {
            Screen.SETTINGS, Screen.HELP -> {
                screen = returnScreen
                animateIn()
                true
            }
            Screen.PAUSE -> {
                fire(Action.RESUME)
                true
            }
            Screen.RESULT -> {
                fire(Action.MAIN_MENU)
                true
            }
            Screen.MAIN -> false
        }
    }

    private fun animateIn() {
        animStart = SystemClock.uptimeMillis()
        rebuild()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    // ---------------------------------------------------------------- layout

    private fun rebuild() {
        if (width == 0 || height == 0) return
        buttons.clear()
        val landscape = width > height
        when (screen) {
            Screen.MAIN -> layoutMain(landscape)
            Screen.PAUSE -> layoutPause()
            Screen.RESULT -> layoutResult()
            Screen.SETTINGS -> layoutSettings()
            Screen.HELP -> layoutHelp()
        }
    }

    private fun layoutMain(landscape: Boolean) {
        val port = !landscape
        val items = mutableListOf<Triple<String, String, String?>>()
        if (hasSave) items += Triple("continue", "继续比赛", "CONTINUE")
        items += Triple("practice", "练习模式", "PRACTICE")
        items += Triple("ai", "对战 AI", "VS COMPUTER")
        items += Triple("settings", "设置", "SETTINGS")
        items += Triple("help", "玩法说明", "HOW TO PLAY")

        var bh: Float
        var gap: Float
        val bw: Float
        val x: Float
        var startY: Float
        if (port) {
            bh = if (height < dp(620f)) dp(48f) else dp(54f)
            gap = dp(10f)
            bw = min(width - safeL - safeR - dp(44f), dp(400f))
            x = (width - bw) / 2f
            var total = items.size * bh + (items.size - 1) * gap
            val minY = safeT + if (height < dp(620f)) height * 0.36f else height * 0.43f
            val maxY = height - safeB - dp(18f) - total
            startY = max(minY, maxY)
            // Short phones: shrink the stack until it fits above the safe area.
            while (startY + total > height - safeB - dp(14f) && bh > dp(34f)) {
                bh -= dp(2f)
                gap = max(dp(4f), gap - dp(1f))
                total = items.size * bh + (items.size - 1) * gap
                startY = max(minY, height - safeB - dp(18f) - total)
            }
        } else {
            bh = dp(48f)
            gap = dp(9f)
            bw = min(width * 0.38f, dp(430f))
            x = min(width * 0.56f, width - safeR - dp(16f) - bw)
            var total = items.size * bh + (items.size - 1) * gap
            if (total > height - safeT - safeB - dp(26f)) {
                bh = dp(38f)
                gap = dp(6f)
                total = items.size * bh + (items.size - 1) * gap
            }
            startY = (height - total) / 2f + dp(4f)
        }
        items.forEachIndexed { i, item ->
            buttons += Btn(
                item.first, item.second, item.third,
                i == 0,
                RectF(x, startY + i * (bh + gap), x + bw, startY + i * (bh + gap) + bh),
            )
        }
    }

    private fun layoutPause() {
        val w = min(width - safeL - safeR - dp(40f), dp(430f))
        val h = min(height - safeT - safeB - dp(30f), dp(384f))
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        panel.set(left, top, left + w, top + h)
        val bw = w - dp(48f)
        val bx = left + dp(24f)
        val bh = dp(46f)
        val gap = dp(9f)
        var y = panel.bottom - dp(30f) - (4 * bh + 3 * gap)
        fun add(id: String, label: String, primary: Boolean = false) {
            buttons += Btn(id, label, null, primary, RectF(bx, y, bx + bw, y + bh))
            y += bh + gap
        }
        add("resume", "继续比赛", true)
        add("restart", "重新开球")
        add("settings", "设置")
        add("quitmenu", "返回主菜单")
    }

    private fun layoutResult() {
        val w = min(width - safeL - safeR - dp(40f), dp(440f))
        val h = min(height - safeT - safeB - dp(24f), dp(318f))
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        panel.set(left, top, left + w, top + h)
        val bw = w - dp(48f)
        val bx = left + dp(24f)
        val bh = dp(48f)
        val gap = dp(10f)
        var y = panel.bottom - dp(26f) - (2 * bh + gap)
        buttons += Btn("retry", "再来一局", null, true, RectF(bx, y, bx + bw, y + bh))
        y += bh + gap
        buttons += Btn("quitmenu", "返回主菜单", null, false, RectF(bx, y, bx + bw, y + bh))
    }

    private fun layoutSettings() {
        val w = min(width - safeL - safeR - dp(32f), dp(560f))
        val top = safeT + dp(76f)
        val h = min(height - top - safeB - dp(12f), dp(466f)).coerceAtLeast(dp(220f))
        val left = (width - w) / 2f
        panel.set(left, top, left + w, top + h)
        val rowH = min(dp(62f), max(dp(40f), h * 0.17f))
        val bx = left + dp(20f)
        val bwidth = w - dp(40f)
        var y = top + dp(18f)
        buttons += Btn("sound", "", null, false, RectF(bx, y, bx + bwidth, y + rowH))
        y += rowH + dp(10f)
        buttons += Btn("haptics", "", null, false, RectF(bx, y, bx + bwidth, y + rowH))
        y += rowH + dp(14f)
        strengthBar.set(bx + dp(150f), y + dp(22f), bx + bwidth - dp(12f), y + dp(30f))
        y += dp(54f)
        val qy = y + dp(22f)
        val qw = (bwidth - dp(28f)) / 3f
        buttons += Btn("q0", "自动", null, false, RectF(bx, qy, bx + qw, qy + dp(34f)))
        buttons += Btn("q1", "高清", null, false, RectF(bx + qw + dp(8f), qy, bx + 2 * qw + dp(8f), qy + dp(34f)))
        buttons += Btn("q2", "省电", null, false, RectF(bx + 2 * (qw + dp(8f)), qy, bx + bwidth, qy + dp(34f)))
        backRect.set(safeL + dp(16f), safeT + dp(16f), safeL + dp(58f), safeT + dp(58f))
    }

    private fun layoutHelp() {
        val w = min(width - safeL - safeR - dp(32f), dp(600f))
        val top = safeT + dp(82f)
        val h = min(height - top - safeB - dp(16f), dp(520f))
        val left = (width - w) / 2f
        panel.set(left, top, left + w, top + h)
        backRect.set(safeL + dp(16f), safeT + dp(16f), safeL + dp(58f), safeT + dp(58f))
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val elapsed = (SystemClock.uptimeMillis() - animStart).coerceAtLeast(0L)
        val t = (elapsed / 280f).coerceIn(0f, 1f)
        val ease = 1f - (1f - t) * (1f - t)
        val alpha = (255 * ease).toInt()
        val animating = t < 1f
        val layer = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), alpha)

        drawBackdrop(canvas)
        when (screen) {
            Screen.MAIN -> drawMain(canvas, ease)
            Screen.PAUSE -> drawPause(canvas)
            Screen.RESULT -> drawResult(canvas)
            Screen.SETTINGS -> drawSettings(canvas)
            Screen.HELP -> drawHelp(canvas)
        }
        canvas.restoreToCount(layer)
        if (animating) postInvalidateOnAnimation()
    }

    private fun drawBackdrop(canvas: Canvas) {
        val opaque = screen == Screen.SETTINGS || screen == Screen.HELP
        paint.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            intArrayOf(
                (if (opaque) 0xFA050708 else 0xE2050708).toInt(),
                (if (opaque) 0xF2070A0B else 0xC8070A0B).toInt(),
                (if (opaque) 0xFA050708 else 0xE2050708).toInt(),
            ),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null

        val cx = if (width > height) width * 0.28f else width * 0.5f
        val cy = if (width > height) height * 0.42f else height * 0.30f
        paint.shader = RadialGradient(
            cx, cy, max(width, height) * 0.55f,
            intArrayOf(0x332FA37A, 0x142FA37A, 0x00000000),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = 0x14D7B56D
        val step = dp(54f)
        var x = -height.toFloat()
        while (x < width + height) {
            canvas.drawLine(x, 0f, x + height, height.toFloat(), paint)
            x += step * 2f
        }
        paint.style = Paint.Style.FILL

        canvas.drawLine(
            safeL + dp(24f), height - safeB - dp(24f),
            width - safeR - dp(24f), height - safeB - dp(24f), paint,
        )
    }

    private fun drawMain(canvas: Canvas, ease: Float) {
        val landscape = width > height
        val titleX: Float
        val titleY: Float
        if (landscape) {
            titleX = safeL + dp(22f)
            titleY = height * 0.30f
        } else {
            titleX = (width - min(width - dp(48f), dp(400f))) / 2f
            titleY = safeT + height * 0.145f
        }
        drawWordmark(
            canvas, titleX, titleY,
            if (landscape) min(dp(50f), width * 0.075f) else min(dp(54f), width * 0.125f),
        )

        text.textAlign = Paint.Align.LEFT
        text.textSize = sp(11f)
        text.letterSpacing = 0.16f
        text.color = 0xAA9FB3A7.toInt()
        val subY = titleY + if (landscape) dp(98f) else dp(104f)
        canvas.drawText("PRECISION POOL · ANDROID 17", titleX, subY, text)
        text.letterSpacing = 0f

        // Decorative table line + eight ball.
        val lineY = subY + dp(21f)
        paint.strokeWidth = dp(1f)
        paint.color = 0x66D7B56D.toInt()
        canvas.drawLine(titleX, lineY, titleX + dp(92f), lineY, paint)
        paint.color = 0x33D7B56D
        canvas.drawLine(titleX + dp(104f), lineY, titleX + dp(230f), lineY, paint)
        val ballCx = titleX + dp(274f)
        val ballCy = lineY - dp(6f)
        drawEightBall(canvas, ballCx, ballCy, dp(22f))

        text.textAlign = Paint.Align.LEFT
        text.textSize = sp(13f)
        text.color = 0xCCF1E6D0.toInt()
        val taglineY = lineY + dp(38f)
        canvas.drawText("一杆之间，计算旋转、摩擦与角度。", titleX, taglineY, text)
        text.textSize = sp(10f)
        text.color = 0x779FB3A7
        canvas.drawText("真实刚体物理 · 动态光照 · 细腻触觉", titleX, taglineY + dp(20f), text)

        if (!landscape && height > dp(620f)) {
            text.textSize = sp(9f)
            text.color = 0x667F9388
            canvas.drawText("TIP · 橫屏可获得更宽阔的瞄准视野", titleX, height - safeB - dp(58f), text)
        }

        // Stats and version, top right.
        text.textAlign = Paint.Align.RIGHT
        text.textSize = sp(10f)
        text.color = 0x99F1E6D0.toInt()
        canvas.drawText("胜场 ${prefs.wins}  /  对局 ${prefs.gamesPlayed}", width - safeR - dp(22f), safeT + dp(26f), text)
        text.textSize = sp(9f)
        text.color = 0x557F9388
        canvas.drawText("v1.0 · BREAK POINT", width - safeR - dp(22f), safeT + dp(43f), text)
        text.textAlign = Paint.Align.LEFT

        buttons.forEach { drawButton(canvas, it) }

        // Footer on landscape.
        if (landscape && buttons.none { it.rect.bottom > height - safeB - dp(34f) }) {
            text.textAlign = Paint.Align.LEFT
            text.textSize = sp(9f)
            text.color = 0x557F9388
            canvas.drawText("OFFLINE · NO ADS · NO ACCOUNT", safeL + dp(24f), height - safeB - dp(22f), text)
        }
    }

    private fun drawWordmark(canvas: Canvas, x: Float, y: Float, size: Float) {
        display.textAlign = Paint.Align.LEFT
        display.textSize = size
        display.letterSpacing = 0.04f
        display.color = CREAM
        canvas.drawText("BREAK", x, y, display)
        val w = display.measureText("BREAK")
        display.color = GOLD
        canvas.drawText("POINT", x + w + size * 0.16f, y, display)
        display.letterSpacing = 0f
        display.color = CREAM
    }

    private fun drawButton(canvas: Canvas, b: Btn) {
        val pressed = pressedId == b.id
        val r = b.rect
        val rad = dp(13f)
        if (b.primary) {
            paint.shader = LinearGradient(
                r.left, r.top, r.right, r.bottom,
                if (pressed) intArrayOf(0xFFEFD79B.toInt(), 0xFFC9A45C.toInt())
                else intArrayOf(0xFFF2DCA6.toInt(), 0xFFC9A45C.toInt()),
                null, Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(r, rad, rad, paint)
            paint.shader = null
            paint.color = 0x33FFFFFF
            canvas.drawRoundRect(RectF(r.left + dp(1f), r.top + dp(1f), r.right - dp(1f), r.top + dp(2f)), rad, rad, paint)
        } else {
            paint.color = if (pressed) 0xCC1B2428.toInt() else 0xB3121A1E.toInt()
            canvas.drawRoundRect(r, rad, rad, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1f)
            paint.color = if (pressed) 0x88D7B56D.toInt() else 0x2EF1E6D0
            canvas.drawRoundRect(r, rad, rad, paint)
            paint.style = Paint.Style.FILL
            paint.color = 0x66D7B56D
            canvas.drawRoundRect(RectF(r.left, r.top + dp(12f), r.left + dp(2f), r.bottom - dp(12f)), dp(2f), dp(2f), paint)
        }

        bold.textAlign = Paint.Align.LEFT
        bold.textSize = sp(15f)
        bold.letterSpacing = 0.04f
        bold.color = if (b.primary) 0xFF121A1D.toInt() else CREAM
        canvas.drawText(b.label, r.left + dp(20f), r.centerY() + sp(5f), bold)
        bold.letterSpacing = 0f

        if (!b.sub.isNullOrBlank() && r.width() > dp(210f)) {
            text.textAlign = Paint.Align.RIGHT
            text.textSize = sp(8.5f)
            text.letterSpacing = 0.12f
            text.color = if (b.primary) 0x99121A1D.toInt() else 0x77F1E6D0
            canvas.drawText(b.sub, r.right - dp(46f), r.centerY() + sp(3f), text)
            text.letterSpacing = 0f
        }
        text.textAlign = Paint.Align.CENTER
        text.textSize = sp(20f)
        text.color = if (b.primary) 0xAA121A1D.toInt() else 0x99D7B56D.toInt()
        canvas.drawText("›", r.right - dp(24f), r.centerY() + sp(7f), text)
    }

    private fun drawPause(canvas: Canvas) {
        drawPanel(canvas, panel)
        text.textAlign = Paint.Align.LEFT
        text.textSize = sp(9f)
        text.letterSpacing = 0.18f
        text.color = 0x99D7B56D.toInt()
        canvas.drawText("PAUSED", panel.left + dp(24f), panel.top + dp(36f), text)
        text.letterSpacing = 0f

        bold.textAlign = Paint.Align.LEFT
        bold.textSize = sp(30f)
        bold.color = CREAM
        canvas.drawText("暂停", panel.left + dp(24f), panel.top + dp(80f), bold)

        val s = pauseSnapshot
        text.textAlign = Paint.Align.LEFT
        text.textSize = sp(11f)
        text.color = 0xAA9FB3A7.toInt()
        val mode = when (s?.mode) {
            GameMode.PRACTICE -> "练习模式"
            GameMode.VS_AI -> "对战 AI"
            null -> ""
        }
        canvas.drawText(mode, panel.left + dp(24f), panel.top + dp(104f), text)
        if (s != null) {
            val turn = if (s.mode == GameMode.PRACTICE || s.currentPlayer == 0) "你的回合" else "AI 回合"
            canvas.drawText("$turn  ·  已出杆 ${s.shots}", panel.left + dp(24f), panel.top + dp(122f), text)
        }
        paint.color = 0x22F1E6D0
        canvas.drawLine(panel.left + dp(24f), panel.top + dp(140f), panel.right - dp(24f), panel.top + dp(140f), paint)
        buttons.forEach { drawButton(canvas, it) }
    }

    private fun drawResult(canvas: Canvas) {
        drawPanel(canvas, panel)
        val accent = if (resultWon) GOLD else 0xFFD9584F.toInt()
        paint.color = (accent and 0x00FFFFFF) or 0x22000000
        canvas.drawCircle(panel.left + dp(44f), panel.top + dp(48f), dp(19f), paint)
        text.textAlign = Paint.Align.CENTER
        text.textSize = sp(22f)
        text.color = accent
        canvas.drawText(if (resultWon) "✓" else "×", panel.left + dp(44f), panel.top + dp(56f), text)

        display.textAlign = Paint.Align.CENTER
        display.textSize = sp(34f)
        display.color = if (resultWon) GOLD else CREAM
        canvas.drawText(if (resultWon) "胜利" else "本局结束", panel.centerX(), panel.top + dp(94f), display)

        text.textAlign = Paint.Align.CENTER
        text.textSize = sp(11f)
        text.color = 0x99F1E6D0.toInt()
        val msg = if (resultMessage.isBlank()) {
            if (resultWon) "你清完了自己的球组，并合法打进 8 号球。" else "AI 拿下了这一局。"
        } else resultMessage
        canvas.drawText(msg, panel.centerX(), panel.top + dp(120f), text)

        paint.color = 0x22F1E6D0
        canvas.drawLine(panel.left + dp(24f), panel.top + dp(144f), panel.right - dp(24f), panel.top + dp(144f), paint)

        val colW = (panel.width() - dp(48f)) / 3f
        val statY = panel.top + dp(174f)
        stat(canvas, panel.left + dp(24f) + colW * 0.5f, statY, "$resultShots", "出杆")
        stat(canvas, panel.left + dp(24f) + colW * 1.5f, statY, "$resultFouls", "犯规")
        stat(canvas, panel.left + dp(24f) + colW * 2.5f, statY, if (resultWon) "W" else "L", "结果")
        buttons.forEach { drawButton(canvas, it) }
    }

    private fun stat(canvas: Canvas, cx: Float, y: Float, value: String, label: String) {
        bold.textAlign = Paint.Align.CENTER
        bold.textSize = sp(20f)
        bold.color = CREAM
        canvas.drawText(value, cx, y, bold)
        text.textAlign = Paint.Align.CENTER
        text.textSize = sp(9f)
        text.color = 0x779FB3A7
        canvas.drawText(label, cx, y + dp(18f), text)
    }

    private fun drawSettings(canvas: Canvas) {
        drawBackButton(canvas)
        text.textAlign = Paint.Align.LEFT
        text.textSize = sp(9f)
        text.letterSpacing = 0.18f
        text.color = 0x99D7B56D.toInt()
        canvas.drawText("PREFERENCES", safeL + dp(80f), safeT + dp(35f), text)
        text.letterSpacing = 0f
        bold.textAlign = Paint.Align.LEFT
        bold.textSize = sp(28f)
        bold.color = CREAM
        canvas.drawText("设置", safeL + dp(80f), safeT + dp(67f), bold)
        drawPanel(canvas, panel)

        val sound = buttons.first { it.id == "sound" }
        val haptics = buttons.first { it.id == "haptics" }
        drawSettingRow(
            canvas, sound.rect, "音效", "击球、碰撞与入袋声音",
            prefs.soundEnabled,
        )
        drawSettingRow(
            canvas, haptics.rect, "触觉反馈", "按碰撞冲量映射振动强度",
            prefs.hapticsEnabled,
        )
        val strengthY = strengthBar.centerY()
        text.textAlign = Paint.Align.LEFT
        text.textSize = sp(13f)
        text.color = CREAM
        canvas.drawText("振动强度", panel.left + dp(20f), strengthY + sp(4f), text)
        text.textSize = sp(9f)
        text.color = 0x779FB3A7
        canvas.drawText("仅支持振幅控制的设备生效", panel.left + dp(20f), strengthBar.bottom + dp(15f), text)
        paint.color = 0x33F1E6D0
        canvas.drawRoundRect(strengthBar, dp(4f), dp(4f), paint)
        val f = ((prefs.hapticStrength - 0.2f) / 0.8f).coerceIn(0f, 1f)
        paint.shader = LinearGradient(
            strengthBar.left, 0f, strengthBar.right, 0f,
            intArrayOf(0xFF2FA37A.toInt(), 0xFFD7B56D.toInt(), 0xFFD9584F.toInt()),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(
            RectF(strengthBar.left, strengthBar.top, strengthBar.left + strengthBar.width() * f, strengthBar.bottom),
            dp(4f), dp(4f), paint,
        )
        paint.shader = null
        paint.color = CREAM
        canvas.drawCircle(strengthBar.left + strengthBar.width() * f, strengthBar.centerY(), dp(9f), paint)
        text.textAlign = Paint.Align.RIGHT
        text.textSize = sp(10f)
        text.color = GOLD
        canvas.drawText("${(prefs.hapticStrength * 100).toInt()}%", strengthBar.right, strengthBar.top - dp(9f), text)

        val q = buttons.first { it.id == "q0" }
        text.textAlign = Paint.Align.LEFT
        text.textSize = sp(13f)
        text.color = CREAM
        canvas.drawText("画质", panel.left + dp(20f), q.rect.top - dp(16f), text)
        text.textSize = sp(9f)
        text.color = 0x779FB3A7
        canvas.drawText("自动将根据设备性能选择", panel.left + dp(20f), q.rect.top - dp(2f), text)
        listOf("q0", "q1", "q2").forEachIndexed { i, id ->
            val b = buttons.first { it.id == id }
            val selected = prefs.quality == i
            paint.color = if (selected) 0x33D7B56D else 0x22161D20
            canvas.drawRoundRect(b.rect, dp(8f), dp(8f), paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1f)
            paint.color = if (selected) 0x99D7B56D.toInt() else 0x22F1E6D0
            canvas.drawRoundRect(b.rect, dp(8f), dp(8f), paint)
            paint.style = Paint.Style.FILL
            text.textAlign = Paint.Align.CENTER
            text.textSize = sp(11f)
            text.color = if (selected) GOLD else 0x99F1E6D0.toInt()
            canvas.drawText(b.label, b.rect.centerX(), b.rect.centerY() + sp(4f), text)
        }

        text.textAlign = Paint.Align.CENTER
        text.textSize = sp(9f)
        text.color = 0x557F9388
        canvas.drawText("设置会立即保存", width / 2f, panel.bottom - dp(16f), text)
    }

    private fun drawSettingRow(canvas: Canvas, r: RectF, title: String, sub: String, on: Boolean) {
        paint.color = 0x3320272B
        canvas.drawRoundRect(r, dp(11f), dp(11f), paint)
        text.textAlign = Paint.Align.LEFT
        text.textSize = sp(13f)
        text.color = CREAM
        canvas.drawText(title, r.left + dp(14f), r.top + dp(25f), text)
        text.textSize = sp(9f)
        text.color = 0x779FB3A7
        canvas.drawText(sub, r.left + dp(14f), r.top + dp(43f), text)
        val tw = dp(42f)
        val th = dp(24f)
        val tr = RectF(r.right - dp(14f) - tw, r.centerY() - th / 2f, r.right - dp(14f), r.centerY() + th / 2f)
        paint.color = if (on) 0xCC2FA37A.toInt() else 0x5520272B
        canvas.drawRoundRect(tr, th / 2f, th / 2f, paint)
        paint.color = CREAM
        val kx = if (on) tr.right - th / 2f else tr.left + th / 2f
        canvas.drawCircle(kx, tr.centerY(), th / 2f - dp(3f), paint)
    }

    private fun drawHelp(canvas: Canvas) {
        drawBackButton(canvas)
        text.textAlign = Paint.Align.LEFT
        text.textSize = sp(9f)
        text.letterSpacing = 0.18f
        text.color = 0x99D7B56D.toInt()
        canvas.drawText("HOW TO PLAY", safeL + dp(80f), safeT + dp(35f), text)
        text.letterSpacing = 0f
        bold.textAlign = Paint.Align.LEFT
        bold.textSize = sp(28f)
        bold.color = CREAM
        canvas.drawText("玩法说明", safeL + dp(80f), safeT + dp(67f), bold)
        drawPanel(canvas, panel)

        val lines = listOf(
            "01   拖动台面调整瞄准方向，虚线显示白球路径。",
            "02   右侧力度条控制出杆力度，越满越强。",
            "03   左下旋转盘控制高杆、缩杆与左右塞。",
            "04   点击右下金色按钮出杆；球在运动时可加速等待。",
            "",
            "8 球规则",
            "· 开球后第一颗合法进袋的球决定你的球组。",
            "· 先碰自己的球组，再进袋即可继续出杆。",
            "· 白球落袋、空杆或先碰错球都会犯规，对手获得自由球。",
            "· 清空自己的球组后，合法打进 8 号球即可获胜。",
            "· 提前打进 8 号球、或 8 号球落袋时白球也落袋，判负。",
            "",
            "技巧",
            "· 高杆让白球跟进，缩杆让白球回拉，侧塞改变库边反弹角。",
            "· 力度越大，滑动摩擦阶段越长，走位越难控制。",
        )
        canvas.save()
        canvas.clipRect(panel.left + dp(1f), panel.top + dp(1f), panel.right - dp(1f), panel.bottom - dp(1f))
        val tight = panel.height() < dp(360f)
        val lineH = if (tight) dp(15f) else dp(21f)
        var y = panel.top + (if (tight) dp(24f) else dp(32f))
        lines.forEach { line ->
            if (line.isEmpty()) {
                y += if (tight) dp(4f) else dp(8f)
                return@forEach
            }
            val header = line == "8 球规则" || line == "技巧"
            text.textAlign = Paint.Align.LEFT
            text.textSize = if (header) sp(11f) else sp(11.5f)
            text.color = if (header) GOLD else 0xCCF1E6D0.toInt()
            val maxW = panel.width() - dp(44f)
            var draw = line
            while (draw.isNotEmpty() && text.measureText(draw) > maxW) {
                var cut = draw.length
                while (cut > 1 && text.measureText(draw.substring(0, cut)) > maxW) cut--
                canvas.drawText(draw.substring(0, cut), panel.left + dp(22f), y, text)
                draw = draw.substring(cut)
                y += lineH - dp(3f)
            }
            if (draw.isNotEmpty()) {
                canvas.drawText(draw, panel.left + dp(22f), y, text)
                y += lineH
            }
            if (header) y += dp(2f)
        }
        canvas.restore()
    }

    private fun drawPanel(canvas: Canvas, r: RectF) {
        paint.shader = LinearGradient(
            r.left, r.top, r.right, r.bottom,
            intArrayOf(0xF012181B.toInt(), 0xE80B1012.toInt()),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(r, dp(20f), dp(20f), paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = 0x33D7B56D
        canvas.drawRoundRect(r, dp(20f), dp(20f), paint)
        paint.style = Paint.Style.FILL
        paint.color = 0x14FFFFFF
        canvas.drawRoundRect(RectF(r.left + dp(1f), r.top + dp(1f), r.right - dp(1f), r.top + dp(2f)), dp(20f), dp(20f), paint)
    }

    private fun drawBackButton(canvas: Canvas) {
        paint.color = 0x33161D20
        canvas.drawCircle(backRect.centerX(), backRect.centerY(), backRect.width() / 2f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = 0x33F1E6D0
        canvas.drawCircle(backRect.centerX(), backRect.centerY(), backRect.width() / 2f, paint)
        paint.style = Paint.Style.FILL
        text.textAlign = Paint.Align.CENTER
        text.textSize = sp(22f)
        text.color = CREAM
        canvas.drawText("‹", backRect.centerX(), backRect.centerY() + sp(7f), text)
    }

    private fun drawEightBall(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        paint.shader = RadialGradient(
            cx - r * 0.35f, cy - r * 0.4f, r * 1.5f,
            intArrayOf(0xFF3A3F42.toInt(), 0xFF08090A.toInt()), null, Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = null
        paint.color = 0xFFF1E6D0.toInt()
        canvas.drawCircle(cx, cy, r * 0.45f, paint)
        text.textAlign = Paint.Align.CENTER
        text.textSize = r * 0.72f
        text.color = 0xFF111416.toInt()
        canvas.drawText("8", cx, cy + r * 0.26f, text)
    }

    // --------------------------------------------------------------- touches

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedId = null
                draggingStrength = screen == Screen.SETTINGS && near(strengthBar, x, y, dp(18f))
                if (draggingStrength) {
                    updateStrength(x)
                    invalidate()
                    return true
                }
                if (screen == Screen.SETTINGS || screen == Screen.HELP) {
                    if (backRect.contains(x, y)) {
                        pressedId = "back"
                        onUiTap?.invoke()
                        invalidate()
                        return true
                    }
                }
                val hit = buttons.firstOrNull { it.rect.contains(x, y) }
                if (hit != null) {
                    pressedId = hit.id
                    onUiTap?.invoke()
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingStrength) {
                    updateStrength(x)
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (draggingStrength) {
                    draggingStrength = false
                    onSettingsChanged?.invoke()
                    return true
                }
                val id = pressedId
                pressedId = null
                invalidate()
                if (id != null) {
                    if (id == "back") {
                        if (backRect.contains(x, y)) {
                            performClick()
                            handleBack()
                        }
                    } else {
                        val hit = buttons.firstOrNull { it.id == id }
                        if (hit != null && hit.rect.contains(x, y)) {
                            performClick()
                            click(id)
                        }
                    }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedId = null
                draggingStrength = false
                invalidate()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun click(id: String) {
        when (id) {
            "practice" -> fire(Action.PRACTICE)
            "ai" -> fire(Action.VS_AI)
            "continue" -> fire(Action.CONTINUE)
            "resume" -> fire(Action.RESUME)
            "restart" -> fire(Action.RESTART)
            "retry" -> fire(Action.RETRY)
            "quitmenu" -> fire(Action.MAIN_MENU)
            "settings" -> showSettings()
            "help" -> showHelp()
            "sound" -> {
                prefs.soundEnabled = !prefs.soundEnabled
                onSettingsChanged?.invoke()
                invalidate()
            }
            "haptics" -> {
                prefs.hapticsEnabled = !prefs.hapticsEnabled
                onSettingsChanged?.invoke()
                invalidate()
            }
            "q0", "q1", "q2" -> {
                prefs.quality = id.substring(1).toInt()
                onSettingsChanged?.invoke()
                invalidate()
            }
        }
    }

    private fun fire(action: Action) {
        onAction?.invoke(action)
    }

    private fun updateStrength(x: Float) {
        val f = ((x - strengthBar.left) / strengthBar.width()).coerceIn(0f, 1f)
        prefs.hapticStrength = 0.2f + 0.8f * f
    }

    private fun near(r: RectF, x: Float, y: Float, pad: Float): Boolean =
        x >= r.left - pad && x <= r.right + pad && y >= r.top - pad && y <= r.bottom + pad

    private fun dp(v: Float): Float = v * d
    private fun sp(v: Float): Float = v * scaled

    companion object {
        private const val CREAM = 0xFFF1E6D0.toInt()
        private const val GOLD = 0xFFD7B56D.toInt()
    }
}

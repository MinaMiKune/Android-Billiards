package com.minamikune.billiards.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import com.minamikune.billiards.game.GameMode
import com.minamikune.billiards.game.MatchSnapshot
import com.minamikune.billiards.game.SessionState
import com.minamikune.billiards.physics.BallGroup
import com.minamikune.billiards.physics.Vec2
import com.minamikune.billiards.resources.FontSet
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * In-game heads-up display and touch controls, drawn over the GL table.
 *
 * Touch handling and control layout live in [HudTouchController] /
 * [computeHudLayout] so the gesture semantics are unit-testable; this View
 * only maps effects to callbacks and draws.
 */
class GameHudView(
    context: Context,
    private val fonts: FontSet = FontSet(),
) : View(context) {
    @Suppress("unused")
    constructor(context: Context) : this(context, FontSet())

    var snapshot: MatchSnapshot? = null
        private set

    var onAimPoint: ((Vec2) -> Unit)? = null
    var onPlaceCue: ((Vec2) -> Unit)? = null
    var onShoot: (() -> Unit)? = null
    var onPause: (() -> Unit)? = null
    var onPower: ((Double) -> Unit)? = null
    var onSpin: ((Double, Double) -> Unit)? = null
    var onFastForward: (() -> Unit)? = null
    var onUiTap: (() -> Unit)? = null
    var screenToTable: ((Float, Float) -> Vec2?)? = null

    private val d = resources.displayMetrics.density
    private val scaled = d * resources.configuration.fontScale
    private var safeL = 0
    private var safeT = 0
    private var safeR = 0
    private var safeB = 0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = fonts.body
        color = CREAM
    }
    private val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = fonts.label
        color = CREAM
    }
    private val number = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = fonts.display
        textAlign = Paint.Align.CENTER
    }

    // Geometry, kept in android.graphics form for drawing; the canonical
    // values come from computeHudLayout (shared with the touch controller).
    private var hudLayout: HudLayout? = null
    private val powerRect = RectF()
    private val pauseRect = RectF()
    private val shootCenter = android.graphics.PointF()
    private val spinCenter = android.graphics.PointF()
    private var spinRadius = 0f
    private var shootRadius = 0f
    private val ffRect = RectF()

    private var localPower = 0.55f
    private var localSide = 0f
    private var localVertical = 0f
    private var lastFrame = 0L

    private val touchController by lazy {
        HudTouchController(
            layout = { hudLayout ?: computeHudLayout(width, height, safeL, safeT, safeR, safeB, d) },
            context = {
                snapshot?.let {
                    HudTouchContext(
                        state = it.state,
                        practice = it.mode == GameMode.PRACTICE,
                        currentPlayer = it.currentPlayer,
                    )
                } ?: HudTouchContext(SessionState.AIMING, practice = true, currentPlayer = 0)
            },
            powerAnchor = { localPower },
            aimMapper = { x, y -> screenToTable?.invoke(x, y) },
        )
    }

    fun setSnapshot(s: MatchSnapshot) {
        snapshot = s
        // While a finger owns the power/spin control, an engine snapshot must
        // not yank the slider or the spin dot from under it.
        if (!touchController.draggingPower) localPower = s.power.toFloat()
        if (!touchController.draggingSpin) {
            // Engine spin is stored in [-0.5, 0.5]; the on-screen disc uses [-1, 1].
            localSide = (s.sideSpin * 2.0).toFloat().coerceIn(-1f, 1f)
            localVertical = (s.verticalSpin * 2.0).toFloat().coerceIn(-1f, 1f)
        }
        invalidate()
    }

    fun setSafeInsets(l: Int, t: Int, r: Int, b: Int) {
        safeL = l
        safeT = t
        safeR = r
        safeB = b
        if (width > 0 && height > 0) layoutControls(width, height)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutControls(w, h)
    }

    private fun layoutControls(w: Int, h: Int) {
        val l = computeHudLayout(w, h, safeL, safeT, safeR, safeB, d)
        hudLayout = l
        powerRect.set(l.powerRect.left, l.powerRect.top, l.powerRect.right, l.powerRect.bottom)
        pauseRect.set(l.pauseRect.left, l.pauseRect.top, l.pauseRect.right, l.pauseRect.bottom)
        shootRadius = l.shootRadius
        shootCenter.set(l.shootCenterX, l.shootCenterY)
        spinRadius = l.spinRadius
        spinCenter.set(l.spinCenterX, l.spinCenterY)
        ffRect.set(l.ffRect.left, l.ffRect.top, l.ffRect.right, l.ffRect.bottom)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = snapshot ?: return
        val now = System.nanoTime()
        val frame = ((now / 1_000_000L) % 100000L).toFloat()
        val pulse = 0.5f + 0.5f * sin(frame / 380f)
        drawTopBar(canvas, s, pulse)
        drawMessage(canvas, s, pulse)
        drawPower(canvas, s, pulse)
        drawSpin(canvas, s)
        drawShoot(canvas, s, pulse)
        if (s.state == SessionState.SIMULATING) drawFastForward(canvas, s, pulse)
        drawBallInHandHint(canvas, s, pulse)
        if (now - lastFrame > 45_000_000L) {
            lastFrame = now
            postInvalidateDelayed(33)
        }
    }

    private fun drawTopBar(canvas: Canvas, s: MatchSnapshot, pulse: Float) {
        val shader = LinearGradient(
            0f, 0f, 0f, safeT + dp(104f),
            intArrayOf(0xE6000000.toInt(), 0xA6000000.toInt(), 0x00000000),
            floatArrayOf(0f, 0.62f, 1f),
            Shader.TileMode.CLAMP,
        )
        paint.shader = shader
        canvas.drawRect(0f, 0f, width.toFloat(), safeT + dp(104f), paint)
        paint.shader = null

        val left = safeL + dp(20f)
        val top = safeT + dp(20f)
        val practice = s.mode == GameMode.PRACTICE
        if (practice) {
            drawPlayer(canvas, left, top, "PRACTICE", "自由练习", true, null, 0, pulse, false)
        } else {
            val leftInfo = s.players.getOrNull(0)
            val rightInfo = s.players.getOrNull(1)
            drawPlayer(
                canvas, left, top,
                (leftInfo?.name ?: "YOU").uppercase(),
                groupLabel(leftInfo?.group), s.currentPlayer == 0, leftInfo?.group,
                remaining(s, leftInfo?.group), pulse, false,
            )
            val rightName = (rightInfo?.name ?: "AI").uppercase()
            drawPlayer(
                canvas, width - safeR - dp(104f), top,
                rightName,
                groupLabel(rightInfo?.group), s.currentPlayer == 1, rightInfo?.group,
                remaining(s, rightInfo?.group), pulse, true,
            )
        }

        // pause button
        paint.style = Paint.Style.FILL
        paint.color = if (touchController.isPressed(HudPress.PAUSE)) 0x55FFFFFF else 0x33000000
        canvas.drawRoundRect(pauseRect, dp(12f), dp(12f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.2f)
        paint.color = 0x55F1E6D0
        canvas.drawRoundRect(pauseRect, dp(12f), dp(12f), paint)
        paint.style = Paint.Style.FILL
        paint.color = CREAM
        val cx = pauseRect.centerX()
        val cy = pauseRect.centerY()
        canvas.drawRoundRect(cx - dp(7f), cy - dp(9f), cx - dp(2.5f), cy + dp(9f), dp(1.5f), dp(1.5f), paint)
        canvas.drawRoundRect(cx + dp(2.5f), cy - dp(9f), cx + dp(7f), cy + dp(9f), dp(1.5f), dp(1.5f), paint)

        if (!practice) {
            val mid = width / 2f
            bold.textSize = sp(10f)
            bold.textAlign = Paint.Align.CENTER
            bold.color = 0x77F1E6D0
            bold.letterSpacing = 0.16f
            canvas.drawText("SHOT ${s.shots}", mid, top + dp(11f), bold)
            bold.letterSpacing = 0f
            paint.color = 0x33FFFFFF
            canvas.drawRect(mid - dp(32f), top + dp(21f), mid + dp(32f), top + dp(22f), paint)
        }
    }

    private fun drawPlayer(
        canvas: Canvas,
        x: Float,
        y: Float,
        name: String,
        group: String,
        active: Boolean,
        groupEnum: BallGroup?,
        remain: Int,
        pulse: Float,
        rightAligned: Boolean,
    ) {
        bold.textSize = sp(16f)
        bold.letterSpacing = 0.10f
        bold.color = if (active) GOLD else 0x88F1E6D0.toInt()
        bold.textAlign = if (rightAligned) Paint.Align.RIGHT else Paint.Align.LEFT
        val anchor = if (rightAligned) x + dp(84f) else x
        canvas.drawText(name, anchor, y + dp(16f), bold)
        bold.letterSpacing = 0f

        textPaint.textSize = sp(9f)
        textPaint.color = 0x99F1E6D0.toInt()
        textPaint.textAlign = if (rightAligned) Paint.Align.RIGHT else Paint.Align.LEFT
        canvas.drawText(group, anchor, y + dp(31f), textPaint)

        if (active) {
            val tx = if (rightAligned) anchor - dp(74f) else anchor + dp(74f)
            paint.color = GOLD
            val path = Path()
            path.moveTo(tx, y + dp(8f))
            path.lineTo(tx + dp(5f), y + dp(12f))
            path.lineTo(tx, y + dp(16f))
            path.close()
            canvas.drawPath(path, paint)
            paint.color = (0x44D7B56D * (0.7f + pulse * 0.3f)).toInt()
            canvas.drawCircle(anchor + if (rightAligned) dp(88f) else dp(0f), y + dp(11f), dp(15f) * (0.85f + 0.15f * pulse), paint)
        }

        if (groupEnum != null && remain > 0) {
            val label = "× $remain"
            textPaint.color = 0xAAF1E6D0.toInt()
            textPaint.textSize = sp(10f)
            textPaint.textAlign = if (rightAligned) Paint.Align.RIGHT else Paint.Align.LEFT
            canvas.drawText(label, anchor, y + dp(45f), textPaint)
        }
    }

    private fun drawMessage(canvas: Canvas, s: MatchSnapshot, pulse: Float) {
        val msg = when (s.state) {
            SessionState.AI_THINKING -> "AI 正在计算线路" + ".".repeat(1 + ((pulse * 3f).toInt() % 3))
            SessionState.BALL_IN_HAND -> "自由球 · 点击台面放置白球"
            SessionState.SIMULATING -> "球在运动"
            SessionState.GAME_OVER -> if (s.winner == 0) "胜利" else "本局结束"
            SessionState.AIMING -> s.message
        }
        if (msg.isBlank()) return
        textPaint.textSize = sp(11f)
        textPaint.color = 0xDDF1E6D0.toInt()
        textPaint.textAlign = Paint.Align.CENTER
        val tw = textPaint.measureText(msg)
        val w = min(width - safeL - safeR - dp(40f), tw + dp(34f))
        val cy = safeT + dp(96f)
        val rect = RectF(width / 2f - w / 2f, cy - dp(16f), width / 2f + w / 2f, cy + dp(16f))
        paint.color = 0x8A050708.toInt()
        canvas.drawRoundRect(rect, dp(16f), dp(16f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = 0x33D7B56D
        canvas.drawRoundRect(rect, dp(16f), dp(16f), paint)
        paint.style = Paint.Style.FILL
        canvas.drawText(msg, width / 2f, cy + sp(4f), textPaint)
    }

    private fun drawPower(canvas: Canvas, s: MatchSnapshot, pulse: Float) {
        val enabled = s.state == SessionState.AIMING && humanTurn(s)
        val r = powerRect
        paint.style = Paint.Style.FILL
        paint.color = 0x66070A0C
        canvas.drawRoundRect(
            r.left - dp(8f), r.top - dp(20f), r.right + dp(8f), r.bottom + dp(26f),
            dp(18f), dp(18f), paint,
        )
        paint.color = 0x55000000
        canvas.drawRoundRect(r, dp(14f), dp(14f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = 0x33FFFFFF
        canvas.drawRoundRect(r, dp(14f), dp(14f), paint)

        val fill = if (enabled) localPower.coerceIn(0.05f, 1f) else s.power.toFloat()
        val filled = RectF(r.left, r.bottom - r.height() * fill, r.right, r.bottom)
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            r.left, r.bottom, r.left, r.top,
            intArrayOf(0xFF2FA37A.toInt(), 0xFFD7B56D.toInt(), 0xFFD9584F.toInt()),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(filled, dp(10f), dp(10f), paint)
        paint.shader = null
        paint.color = 0x33FFFFFF
        for (i in 1..3) {
            val y = r.bottom - r.height() * (i / 4f)
            canvas.drawLine(r.left + dp(5f), y, r.right - dp(5f), y, paint)
        }
        val knobY = r.bottom - r.height() * fill
        paint.color = 0xFFF1E6D0.toInt()
        canvas.drawCircle(r.centerX(), knobY, dp(7f), paint)
        if (enabled) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1.5f)
            paint.color = (0x88D7B56D * (0.5f + pulse * 0.5f)).toInt()
            canvas.drawCircle(r.centerX(), knobY, dp(11f) + dp(2f) * pulse, paint)
            paint.style = Paint.Style.FILL
        }

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = sp(9f)
        textPaint.color = if (enabled) 0xAAF1E6D0.toInt() else 0x44F1E6D0
        canvas.drawText("POWER", r.centerX(), r.top - dp(8f), textPaint)
        bold.textAlign = Paint.Align.CENTER
        bold.textSize = sp(11f)
        bold.color = if (enabled) GOLD else 0x55F1E6D0
        canvas.drawText("${(fill * 100).toInt()}", r.centerX(), r.bottom + dp(17f), bold)
    }

    private fun drawSpin(canvas: Canvas, s: MatchSnapshot) {
        val enabled = s.state == SessionState.AIMING && humanTurn(s)
        val cx = spinCenter.x
        val cy = spinCenter.y
        paint.color = 0x77070A0C
        canvas.drawCircle(cx, cy, spinRadius + dp(10f), paint)
        paint.color = 0x55000000
        canvas.drawCircle(cx, cy, spinRadius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = 0x44FFFFFF
        canvas.drawCircle(cx, cy, spinRadius, paint)
        paint.color = 0x33FFFFFF
        canvas.drawLine(cx - spinRadius * 0.72f, cy, cx + spinRadius * 0.72f, cy, paint)
        canvas.drawLine(cx, cy - spinRadius * 0.72f, cx, cy + spinRadius * 0.72f, paint)
        val dotX = cx + localSide * spinRadius * 0.72f
        val dotY = cy - localVertical * spinRadius * 0.72f
        paint.style = Paint.Style.FILL
        paint.color = if (enabled) 0xFFE9534D.toInt() else 0x66E9534D
        canvas.drawCircle(dotX, dotY, dp(7f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = 0x99F1E6D0.toInt()
        canvas.drawCircle(dotX, dotY, dp(10f), paint)
        paint.style = Paint.Style.FILL

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = sp(9f)
        textPaint.color = if (enabled) 0xAAF1E6D0.toInt() else 0x44F1E6D0
        canvas.drawText("SPIN", cx, cy + spinRadius + dp(20f), textPaint)
        textPaint.textSize = sp(8f)
        textPaint.color = 0x77F1E6D0
        canvas.drawText(spinLabel(), cx, cy + spinRadius + dp(32f), textPaint)
    }

    private fun spinLabel(): String {
        val v = when {
            localVertical > 0.08 -> "跟进"
            localVertical < -0.08 -> "缩杆"
            else -> "平杆"
        }
        val side = when {
            localSide > 0.08 -> "右塞"
            localSide < -0.08 -> "左塞"
            else -> "无塞"
        }
        return "$v · $side"
    }

    private fun drawShoot(canvas: Canvas, s: MatchSnapshot, pulse: Float) {
        val enabled = s.state == SessionState.AIMING && humanTurn(s)
        val cx = shootCenter.x
        val cy = shootCenter.y
        paint.style = Paint.Style.FILL
        if (enabled) {
            paint.color = (0x44D7B56D * (0.4f + 0.6f * pulse)).toInt()
            canvas.drawCircle(cx, cy, shootRadius + dp(8f) + dp(3f) * pulse, paint)
        }
        paint.color = if (enabled) {
            if (touchController.isPressed(HudPress.SHOOT)) 0xFFE6C57A.toInt() else 0xFFD7B56D.toInt()
        } else {
            0x554B4A45
        }
        canvas.drawCircle(cx, cy, shootRadius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.5f)
        paint.color = if (enabled) 0xAAFFF3D8.toInt() else 0x33FFFFFF
        canvas.drawCircle(cx, cy, shootRadius - dp(2f), paint)
        paint.style = Paint.Style.FILL
        bold.textAlign = Paint.Align.CENTER
        bold.textSize = sp(16f)
        bold.letterSpacing = 0.08f
        bold.color = if (enabled) 0xFF17130B.toInt() else 0x665C574C
        canvas.drawText("击球", cx, cy + sp(6f), bold)
        bold.letterSpacing = 0f
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = sp(8f)
        textPaint.color = if (enabled) 0x9917130B.toInt() else 0x445C574C
        canvas.drawText("SHOOT", cx, cy + sp(20f), textPaint)
    }

    private fun drawFastForward(canvas: Canvas, s: MatchSnapshot, pulse: Float) {
        paint.color = if (touchController.isPressed(HudPress.FAST_FORWARD)) 0xAA3A3428.toInt() else 0x7A1B1A17
        canvas.drawRoundRect(ffRect, dp(18f), dp(18f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = 0x44D7B56D
        canvas.drawRoundRect(ffRect, dp(18f), dp(18f), paint)
        paint.style = Paint.Style.FILL
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = sp(10f)
        textPaint.color = (0xCCF1E6D0 * (0.7f + 0.3f * pulse)).toInt()
        canvas.drawText("加速 »", ffRect.centerX(), ffRect.centerY() + sp(4f), textPaint)
    }

    private fun drawBallInHandHint(canvas: Canvas, s: MatchSnapshot, pulse: Float) {
        if (s.state != SessionState.BALL_IN_HAND) return
        val cy = spinCenter.y - spinRadius - dp(26f)
        paint.color = (0x33D7B56D * (0.5f + 0.5f * pulse)).toInt()
        canvas.drawCircle(safeL + dp(60f), cy, dp(4f), paint)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = sp(10f)
        textPaint.color = 0xCCF1E6D0.toInt()
        canvas.drawText("白球自由摆放", safeL + dp(72f), cy + sp(3f), textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (snapshot == null) return false
        val controller = touchController
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> dispatchTouch(controller.onDown(event.getPointerId(0), event.x, event.y))
            MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                dispatchTouch(controller.onDown(event.getPointerId(idx), event.getX(idx), event.getY(idx)))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    dispatchTouch(controller.onMove(event.getPointerId(i), event.getX(i), event.getY(i)))
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val idx = event.actionIndex
                dispatchTouch(controller.onUp(event.getPointerId(idx), event.getX(idx), event.getY(idx)))
                performClick()
            }
            MotionEvent.ACTION_UP -> {
                dispatchTouch(controller.onUp(event.getPointerId(0), event.x, event.y))
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> controller.onCancel()
        }
        invalidate()
        return true
    }

    private fun dispatchTouch(effects: List<HudEffect>) {
        effects.forEach { effect ->
            when (effect) {
                is HudEffect.PowerChanged -> {
                    localPower = effect.value.toFloat()
                    onPower?.invoke(effect.value)
                }
                is HudEffect.SpinChanged -> {
                    // Engine spin is [-0.5, 0.5]; the on-screen disc uses [-1, 1].
                    localSide = (effect.side * 2.0).toFloat().coerceIn(-1f, 1f)
                    localVertical = (effect.vertical * 2.0).toFloat().coerceIn(-1f, 1f)
                    onSpin?.invoke(effect.side, effect.vertical)
                }
                is HudEffect.AimAt -> onAimPoint?.invoke(effect.point)
                is HudEffect.PlaceCue -> onPlaceCue?.invoke(effect.point)
                HudEffect.Shoot -> onShoot?.invoke()
                HudEffect.Pause -> onPause?.invoke()
                HudEffect.FastForward -> onFastForward?.invoke()
                HudEffect.UiTap -> onUiTap?.invoke()
            }
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun humanTurn(s: MatchSnapshot): Boolean =
        s.mode == GameMode.PRACTICE || s.currentPlayer == 0

    private fun remaining(s: MatchSnapshot, group: BallGroup?): Int {
        if (group == null || group == BallGroup.UNKNOWN) return 0
        val ids = when (group) {
            BallGroup.SOLIDS -> (1..7).toSet()
            BallGroup.STRIPES -> (9..15).toSet()
            else -> emptySet()
        }
        return s.balls.count { it.active && it.id in ids }
    }

    private fun groupLabel(group: BallGroup?): String = when (group) {
        BallGroup.SOLIDS -> "SOLIDS · 全色"
        BallGroup.STRIPES -> "STRIPES · 花色"
        BallGroup.EIGHT -> "8 号球"
        BallGroup.CUE -> "白球"
        else -> "OPEN · 未分组"
    }

    private fun dp(v: Float): Float = v * d
    private fun sp(v: Float): Float = v * scaled

    companion object {
        private const val CREAM = 0xFFF1E6D0.toInt()
        private const val GOLD = 0xFFD7B56D.toInt()
    }
}

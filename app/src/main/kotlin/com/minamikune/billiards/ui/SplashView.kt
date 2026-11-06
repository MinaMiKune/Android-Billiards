package com.minamikune.billiards.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import com.minamikune.billiards.platform.GameConfig
import com.minamikune.billiards.resources.FontSet
import kotlin.math.min

/**
 * Branded startup overlay drawn with Canvas. It is intentionally a plain View, not
 * a separate Activity: the game surface can initialize behind it while the brand is
 * visible, and the overlay is removed without a second window transition.
 */
@SuppressLint("ViewConstructor")
class SplashView(
    context: Context,
    private val config: GameConfig,
    private val fonts: FontSet,
) : View(context) {
    var onFinished: (() -> Unit)? = null

    private var startedAt = 0L
    private var finished = false
    private val d = resources.displayMetrics.density

    private val background = Paint().apply {
        shader = LinearGradient(
            0f, 0f, 0f, 1f,
            intArrayOf(Color.rgb(13, 29, 25), Color.rgb(3, 7, 8)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ball = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.4f * d
        strokeCap = Paint.Cap.ROUND
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = fonts.display
        color = Color.rgb(244, 234, 211)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.08f
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = fonts.label
        color = Color.rgb(196, 162, 95)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.28f
    }
    private val progress = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2f * d
        strokeCap = Paint.Cap.ROUND
        color = Color.rgb(196, 162, 95)
    }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2f * d
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(40, 244, 234, 211)
    }

    fun start() {
        if (startedAt != 0L) return
        startedAt = SystemClock.uptimeMillis()
        visibility = VISIBLE
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        // Size-dependent shader is rebuilt only when the view changes size.
        glow.shader = RadialGradient(
            w * 0.5f, h * 0.43f, w * 0.52f,
            intArrayOf(Color.argb(52, 196, 162, 95), Color.TRANSPARENT),
            null,
            Shader.TileMode.CLAMP,
        )
    }

    val isFinished: Boolean get() = finished

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (startedAt == 0L) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val elapsed = SystemClock.uptimeMillis() - startedAt
        val total = config.splashMinMillis + config.splashFadeMillis
        val fade = if (elapsed <= config.splashMinMillis || config.splashFadeMillis == 0L) {
            1f
        } else {
            (1f - (elapsed - config.splashMinMillis).toFloat() / config.splashFadeMillis).coerceIn(0f, 1f)
        }
        val intro = (elapsed / 420f).coerceIn(0f, 1f)

        canvas.save()
        canvas.scale(w, h)
        canvas.drawRect(0f, 0f, 1f, 1f, background)
        canvas.restore()

        // A cue line and three balls form a compact, non-asset-dependent mark.
        val cx = w * 0.5f
        val cy = h * 0.43f
        glow.alpha = (255 * fade).toInt()
        canvas.drawCircle(cx, cy, w * 0.52f, glow)

        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(-16f)
        canvas.scale(intro, 1f)
        line.color = Color.argb((150 * fade).toInt(), 244, 234, 211)
        line.strokeWidth = 2f * d
        canvas.drawLine(-w * 0.30f, 0f, w * 0.09f, 0f, line)
        val radius = min(w, h) * 0.036f
        ball.color = Color.argb((255 * fade).toInt(), 244, 234, 211)
        canvas.drawCircle(w * 0.08f, 0f, radius, ball)
        ball.color = Color.argb((255 * fade).toInt(), 196, 162, 95)
        canvas.drawCircle(w * 0.17f, -radius * 0.4f, radius * 0.72f, ball)
        ball.color = Color.argb((255 * fade).toInt(), 86, 39, 32)
        canvas.drawCircle(w * 0.245f, -radius * 0.55f, radius * 0.58f, ball)
        canvas.restore()

        titlePaint.alpha = (255 * fade * intro).toInt()
        titlePaint.textSize = min(w, h) * 0.082f
        canvas.drawText("BREAK POINT", cx, h * 0.58f, titlePaint)
        subPaint.alpha = (220 * fade * intro).toInt()
        subPaint.textSize = min(w, h) * 0.026f
        canvas.drawText("桌球 · 8-BALL", cx, h * 0.63f, subPaint)

        val trackW = min(w * 0.42f, 220f * d)
        val left = cx - trackW * 0.5f
        val y = h * 0.80f
        track.alpha = (255 * fade * intro).toInt()
        canvas.drawLine(left, y, left + trackW, y, track)
        val p = (elapsed.toFloat() / total).coerceIn(0f, 1f)
        progress.alpha = (255 * fade * intro).toInt()
        canvas.drawLine(left, y, left + trackW * p, y, progress)

        if (elapsed >= total && !finished) {
            finished = true
            // Never remove this view from its parent in the middle of dispatchDraw.
            val callback = onFinished
            if (callback != null) post(callback)
        } else if (!finished) {
            postInvalidateOnAnimation()
        }
    }
}

package com.minamikune.billiards.gl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.random.Random

/**
 * Procedural artwork. Everything is generated at runtime so the APK stays small and
 * there are no bitmap assets whose resolution breaks on tablets/foldables.
 */
object BallArt {
    private val ballColors = intArrayOf(
        Color.rgb(246, 244, 236), // cue
        Color.rgb(244, 196, 28),  // 1 yellow
        Color.rgb(28, 82, 158),   // 2 blue
        Color.rgb(206, 38, 44),   // 3 red
        Color.rgb(102, 48, 140),  // 4 purple
        Color.rgb(236, 122, 28),  // 5 orange
        Color.rgb(30, 140, 82),   // 6 green
        Color.rgb(122, 32, 36),   // 7 maroon
        Color.rgb(20, 20, 22),    // 8 black
        Color.rgb(244, 196, 28),
        Color.rgb(28, 82, 158),
        Color.rgb(206, 38, 44),
        Color.rgb(102, 48, 140),
        Color.rgb(236, 122, 28),
        Color.rgb(30, 140, 82),
        Color.rgb(122, 32, 36),
    )

    fun ball(id: Int): Bitmap {
        val w = 512
        val h = 256
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (id == 0) {
            c.drawColor(Color.rgb(247, 245, 238))
            // A few tiny red registration dots, like a measured cue ball.
            paint.color = Color.rgb(196, 44, 48)
            for (u in floatArrayOf(0.08f, 0.36f, 0.64f, 0.92f)) {
                c.drawCircle(w * u, h * 0.25f, 5f, paint)
                c.drawCircle(w * ((u + 0.14f) % 1f), h * 0.74f, 4f, paint)
            }
            shadePoles(c, w, h)
            return bmp
        }

        val color = ballColors[id.coerceIn(0, 15)]
        val stripe = id >= 9
        if (stripe) {
            c.drawColor(Color.rgb(247, 245, 238))
            paint.color = color
            c.drawRect(0f, h * 0.22f, w.toFloat(), h * 0.78f, paint)
            // thin white split lines give the stripe a printed edge
            paint.strokeWidth = 2f
            paint.color = Color.argb(80, 255, 255, 255)
            c.drawLine(0f, h * 0.22f, w.toFloat(), h * 0.22f, paint)
            c.drawLine(0f, h * 0.78f, w.toFloat(), h * 0.78f, paint)
        } else {
            c.drawColor(color)
        }

        // Two number patches, on opposite sides of the ball.
        for (u in floatArrayOf(0.25f, 0.75f)) {
            val cx = w * u
            val cy = h * 0.5f
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(247, 245, 238)
            c.drawCircle(cx, cy, h * 0.155f, paint)
            val text = if (id == 8) "8" else id.toString()
            paint.color = Color.rgb(24, 24, 24)
            paint.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = h * 0.19f
            val metrics = paint.fontMetrics
            c.drawText(text, cx, cy - (metrics.ascent + metrics.descent) / 2f, paint)
        }
        shadePoles(c, w, h)
        return bmp
    }

    private fun shadePoles(c: Canvas, w: Int, h: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(Color.argb(48, 0, 0, 0), Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(60, 0, 0, 0)),
            floatArrayOf(0f, 0.22f, 0.78f, 1f),
            Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        // Fine print/linen texture.
        val speck = Paint()
        speck.color = Color.argb(14, 255, 255, 255)
        val rnd = Random(11)
        repeat(2600) {
            c.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h, 0.6f, speck)
        }
    }

    fun cloth(): Bitmap {
        val size = 512
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint()
        p.shader = LinearGradient(
            0f, 0f, size.toFloat(), size.toFloat(),
            intArrayOf(Color.rgb(16, 92, 62), Color.rgb(12, 76, 52), Color.rgb(14, 84, 57)),
            null, Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p)
        val rnd = Random(23)
        val fiber = Paint()
        fiber.strokeWidth = 1f
        repeat(9000) {
            val x = rnd.nextFloat() * size
            val y = rnd.nextFloat() * size
            val len = 2f + rnd.nextFloat() * 7f
            val light = rnd.nextInt(30, 62)
            fiber.color = Color.argb(light, 120, 220, 170)
            c.drawLine(x, y, x + len, y + rnd.nextFloat() * 2f - 1f, fiber)
        }
        // faint milled nap direction
        val weave = Paint()
        weave.color = Color.argb(11, 0, 30, 20)
        for (i in 0 until size step 4) {
            c.drawLine(0f, i.toFloat(), size.toFloat(), i.toFloat(), weave)
        }
        return bmp
    }

    fun wood(): Bitmap {
        val size = 512
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(58, 30, 18))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val rnd = Random(5)
        for (i in 0 until 190) {
            val y = rnd.nextFloat() * size
            val alpha = rnd.nextInt(18, 70)
            val warm = rnd.nextInt(0, 28)
            p.color = Color.argb(alpha, 120 + warm, 72 + warm / 2, 38)
            p.strokeWidth = 0.6f + rnd.nextFloat() * 3.4f
            p.style = Paint.Style.STROKE
            val path = android.graphics.Path()
            path.moveTo(-20f, y)
            var x = -20f
            var yy = y
            while (x < size + 20f) {
                x += 24f
                yy += (rnd.nextFloat() - 0.5f) * 5f
                path.lineTo(x, yy)
            }
            c.drawPath(path, p)
        }
        val gloss = Paint()
        gloss.shader = LinearGradient(
            0f, 0f, 0f, size.toFloat(),
            intArrayOf(Color.argb(90, 255, 205, 130), Color.TRANSPARENT, Color.argb(80, 0, 0, 0)),
            null,
            Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), gloss)
        return bmp
    }

    fun shadow(): Bitmap {
        val size = 128
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(
            size / 2f, size / 2f, size / 2f,
            intArrayOf(Color.argb(180, 0, 0, 0), Color.argb(95, 0, 0, 0), Color.TRANSPARENT),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP,
        )
        c.drawCircle(size / 2f, size / 2f, size / 2f, p)
        return bmp
    }

    fun floor(): Bitmap {
        val size = 512
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(13, 14, 16))
        val p = Paint()
        val rnd = Random(91)
        repeat(6000) {
            p.color = Color.argb(rnd.nextInt(4, 16), 80, 70, 58)
            c.drawCircle(rnd.nextFloat() * size, rnd.nextFloat() * size, 1f + rnd.nextFloat() * 2f, p)
        }
        return bmp
    }
}

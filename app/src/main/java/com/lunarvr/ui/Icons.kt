package com.lunarvr.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.PI

/**
 * Minimal, clean vector glyphs drawn with Canvas (no icon assets).
 * All icons are drawn into a [size] x [size] box at (x, y).
 */
object Icons {

    const val NONE = 0
    const val PLAY = 1
    const val GRID = 2
    const val GEAR = 3
    const val POWER = 4
    const val CLOCK = 5
    const val INFO = 6
    const val CHIP = 7
    const val HAND = 8
    const val BACK = 9
    const val MOON = 10
    const val POINT = 11
    const val RAY = 12
    const val HEAD = 13
    const val LINES = 14
    const val PINCH = 15

    fun draw(canvas: Canvas, icon: Int, x: Float, y: Float, size: Float, color: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = color
        val c = size / 2f
        when (icon) {
            PLAY -> {
                paint.style = Paint.Style.FILL
                val p = Path()
                p.moveTo(x + c - size * 0.22f, y + c - size * 0.30f)
                p.lineTo(x + c - size * 0.22f, y + c + size * 0.30f)
                p.lineTo(x + c + size * 0.32f, y + c)
                p.close()
                canvas.drawPath(p, paint)
            }
            GRID -> {
                paint.style = Paint.Style.FILL
                val s = size * 0.30f
                val g = size * 0.12f
                val x0 = x + c - s - g / 2f
                val y0 = y + c - s - g / 2f
                for (i in 0..1) for (j in 0..1) {
                    val r = RectF(x0 + i * (s + g), y0 + j * (s + g), x0 + i * (s + g) + s, y0 + j * (s + g) + s)
                    canvas.drawRoundRect(r, 4f, 4f, paint)
                }
            }
            GEAR -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = size * 0.10f
                canvas.drawCircle(x + c, y + c, size * 0.24f, paint)
                paint.strokeWidth = size * 0.11f
                for (i in 0 until 8) {
                    val a = PI * i / 4f
                    val r0 = size * 0.34f
                    val r1 = size * 0.46f
                    canvas.drawLine(
                        x + c + kotlin.math.cos(a) * r0, y + c + kotlin.math.sin(a) * r0,
                        x + c + kotlin.math.cos(a) * r1, y + c + kotlin.math.sin(a) * r1, paint
                    )
                }
            }
            POWER -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = size * 0.11f
                paint.strokeCap = Paint.Cap.ROUND
                val r = size * 0.34f
                canvas.drawArc(
                    RectF(x + c - r, y + c - r, x + c + r, y + c + r),
                    -60f, 300f, false, paint
                )
                canvas.drawLine(x + c, y + c - size * 0.46f, x + c, y + c - size * 0.05f, paint)
            }
            CLOCK -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = size * 0.09f
                paint.strokeCap = Paint.Cap.ROUND
                canvas.drawCircle(x + c, y + c, size * 0.42f, paint)
                canvas.drawLine(x + c, y + c, x + c, y + c - size * 0.24f, paint)
                canvas.drawLine(x + c, y + c, x + c + size * 0.18f, y + c + size * 0.06f, paint)
            }
            INFO -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = size * 0.09f
                paint.strokeCap = Paint.Cap.ROUND
                canvas.drawCircle(x + c, y + c, size * 0.42f, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(x + c, y + c - size * 0.18f, size * 0.055f, paint)
                paint.style = Paint.Style.STROKE
                canvas.drawLine(x + c, y + c - size * 0.04f, x + c, y + c + size * 0.22f, paint)
            }
            CHIP -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = size * 0.09f
                val r = RectF(x + c - size * 0.28f, y + c - size * 0.28f, x + c + size * 0.28f, y + c + size * 0.28f)
                canvas.drawRoundRect(r, 6f, 6f, paint)
                canvas.drawRoundRect(r.inset(size * 0.12f, size * 0.12f), 3f, 3f, paint)
                for (i in -1..1) {
                    val o = i * size * 0.16f
                    canvas.drawLine(x + c + o, y + c - size * 0.28f, x + c + o, y + c - size * 0.42f, paint)
                    canvas.drawLine(x + c + o, y + c + size * 0.28f, x + c + o, y + c + size * 0.42f, paint)
                    canvas.drawLine(x + c - size * 0.28f, y + c + o, x + c - size * 0.42f, y + c + o, paint)
                    canvas.drawLine(x + c + size * 0.28f, y + c + o, x + c + size * 0.42f, y + c + o, paint)
                }
            }
            HAND -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = size * 0.10f
                paint.strokeCap = Paint.Cap.ROUND
                // simple open hand
                val bx = x + c - size * 0.22f
                val bw = size * 0.44f
                val by = y + c + size * 0.02f
                val bh = size * 0.36f
                canvas.drawRoundRect(RectF(bx, by, bx + bw, by + bh), 8f, 8f, paint)
                for (i in 0..3) {
                    val fx = bx + size * 0.07f + i * (bw / 3.4f)
                    canvas.drawLine(fx, by + size * 0.02f, fx, y + c - size * 0.34f + i * size * 0.02f, paint)
                }
                canvas.drawLine(bx, by + bh * 0.3f, x + c - size * 0.38f, y + c - size * 0.06f, paint)
            }
            BACK -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = size * 0.11f
                paint.strokeCap = Paint.Cap.ROUND
                canvas.drawLine(x + c - size * 0.22f, y + c, x + c + size * 0.30f, y + c, paint)
                canvas.drawLine(x + c + size * 0.05f, y + c - size * 0.22f, x + c - size * 0.22f, y + c, paint)
                canvas.drawLine(x + c + size * 0.05f, y + c + size * 0.22f, x + c - size * 0.22f, y + c, paint)
            }
            MOON -> {
                paint.style = Paint.Style.FILL
                val p = Path()
                p.addCircle(x + c, y + c, size * 0.44f, Path.Direction.CW)
                p.addCircle(x + c + size * 0.20f, y + c - size * 0.10f, size * 0.38f, Path.Direction.CCW)
                canvas.drawPath(p, paint)
            }
            POINT -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = size * 0.11f
                paint.strokeCap = Paint.Cap.ROUND
                canvas.drawLine(x + c, y + c + size * 0.42f, x + c, y + c - size * 0.34f, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(x + c, y + c - size * 0.34f, size * 0.09f, paint)
                paint.style = Paint.Style.STROKE
                canvas.drawRoundRect(
                    RectF(x + c - size * 0.26f, y + c + size * 0.06f, x + c + size * 0.26f, y + c + size * 0.46f),
                    8f, 8f, paint
                )
            }
            RAY -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = size * 0.09f
                paint.strokeCap = Paint.Cap.ROUND
                canvas.drawLine(x + c - size * 0.34f, y + c + size * 0.34f, x + c + size * 0.22f, y + c - size * 0.22f, paint)
                canvas.drawCircle(x + c + size * 0.30f, y + c - size * 0.30f, size * 0.10f, paint)
            }
            HEAD -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = size * 0.09f
                paint.strokeCap = Paint.Cap.ROUND
                canvas.drawCircle(x + c - size * 0.06f, y + c, size * 0.34f, paint)
                // motion arc
                canvas.drawArc(
                    RectF(x + c + size * 0.02f, y + c - size * 0.42f, x + c + size * 0.52f, y + c + size * 0.42f),
                    -50f, 100f, false, paint
                )
            }
            LINES -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = size * 0.10f
                paint.strokeCap = Paint.Cap.ROUND
                for (i in -1..1) {
                    canvas.drawLine(
                        x + c - size * 0.34f, y + c + i * size * 0.22f,
                        x + c + size * 0.34f, y + c + i * size * 0.22f, paint
                    )
                }
            }
            PINCH -> {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(x + c - size * 0.10f, y + c + size * 0.18f, size * 0.11f, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = size * 0.10f
                paint.strokeCap = Paint.Cap.ROUND
                canvas.drawLine(x + c - size * 0.10f, y + c + size * 0.06f, x + c, y + c - size * 0.30f, paint)
                canvas.drawLine(x + c + size * 0.26f, y + c + size * 0.30f, x + c + size * 0.02f, y + c - size * 0.14f, paint)
            }
        }
    }
}

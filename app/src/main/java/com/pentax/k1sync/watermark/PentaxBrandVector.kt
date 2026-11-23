package com.pentax.k1sync.watermark

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Resolution-independent Canvas vector renderer for the official PENTAX brand identity:
 * - Authentic wide-stance geometric PENTAX logotype (custom vector paths, razor sharp at 36MP)
 * - Iconic PENTAX K-1 pointed pentaprism silhouette crest
 */
object PentaxBrandVector {

    /**
     * Aspect ratio (width / height) of the geometric PENTAX wordmark.
     * Official PENTAX wordmark is wide and bold (~5.55 : 1).
     */
    const val WORDMARK_ASPECT = 5.55f

    /**
     * Draws the official geometric PENTAX wordmark inside [bounds].
     * Each glyph is constructed in normalized coordinates (height = 1.0, total width = 5.55)
     * matching the signature heavy, crisp, extended rectangular proportions of the PENTAX logo.
     */
    fun drawPentaxWordmark(canvas: Canvas, bounds: RectF, color: Int, shadowRadius: Float = 0f, shadowColor: Int = 0) {
        val h = bounds.height()
        val w = bounds.width()
        if (h <= 0f || w <= 0f) return

        val sx = w / WORDMARK_ASPECT
        val sy = h

        fun x(nx: Float) = bounds.left + nx * sx
        fun y(ny: Float) = bounds.top + ny * sy

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
            if (shadowRadius > 0f) {
                setShadowLayer(shadowRadius, 0f, shadowRadius * 0.35f, shadowColor)
            }
        }

        val t = 0.23f // heavy geometric stroke thickness
        val path = Path()

        // --- Letter P (x: 0.00 .. 0.80) ---
        // Stem
        path.addRect(x(0.00f), y(0.00f), x(t), y(1.00f), Path.Direction.CW)
        // Top bar
        path.addRect(x(0.00f), y(0.00f), x(0.62f), y(t), Path.Direction.CW)
        // Mid bar
        path.addRect(x(0.00f), y(0.45f), x(0.62f), y(0.45f + t), Path.Direction.CW)
        // Outer bowl right curve (clean D-curve)
        val pOuter = Path().apply {
            moveTo(x(0.56f), y(0.00f))
            cubicTo(x(0.84f), y(0.00f), x(0.84f), y(0.45f + t), x(0.56f), y(0.45f + t))
            lineTo(x(0.56f), y(0.45f))
            cubicTo(x(0.60f), y(0.45f), x(0.60f), y(t), x(0.56f), y(t))
            close()
        }
        path.addPath(pOuter)

        // --- Letter E (x: 0.94 .. 1.70) ---
        val ex = 0.94f
        path.addRect(x(ex), y(0.00f), x(ex + t), y(1.00f), Path.Direction.CW)
        path.addRect(x(ex), y(0.00f), x(ex + 0.76f), y(t), Path.Direction.CW)
        path.addRect(x(ex), y(0.39f), x(ex + 0.66f), y(0.39f + t * 0.94f), Path.Direction.CW)
        path.addRect(x(ex), y(1.00f - t), x(ex + 0.76f), y(1.00f), Path.Direction.CW)

        // --- Letter N (x: 1.85 .. 2.71) ---
        val nx0 = 1.85f
        val nw = 0.86f
        path.addRect(x(nx0), y(0.00f), x(nx0 + t), y(1.00f), Path.Direction.CW)
        path.addRect(x(nx0 + nw - t), y(0.00f), x(nx0 + nw), y(1.00f), Path.Direction.CW)
        val nDiag = Path().apply {
            moveTo(x(nx0), y(0.00f))
            lineTo(x(nx0 + t * 1.15f), y(0.00f))
            lineTo(x(nx0 + nw), y(1.00f))
            lineTo(x(nx0 + nw - t * 1.15f), y(1.00f))
            close()
        }
        path.addPath(nDiag)

        // --- Letter T (x: 2.84 .. 3.66) ---
        val tx0 = 2.84f
        val tw = 0.82f
        path.addRect(x(tx0), y(0.00f), x(tx0 + tw), y(t), Path.Direction.CW)
        val tStemL = tx0 + (tw - t) / 2f
        path.addRect(x(tStemL), y(0.00f), x(tStemL + t), y(1.00f), Path.Direction.CW)

        // --- Letter A (x: 3.68 .. 4.62) ---
        val ax0 = 3.68f
        val aw = 0.94f
        val aMid = ax0 + aw / 2f
        val aLeftLeg = Path().apply {
            moveTo(x(aMid - t * 0.56f), y(0.00f))
            lineTo(x(aMid + t * 0.56f), y(0.00f))
            lineTo(x(ax0 + t * 1.08f), y(1.00f))
            lineTo(x(ax0), y(1.00f))
            close()
        }
        val aRightLeg = Path().apply {
            moveTo(x(aMid - t * 0.56f), y(0.00f))
            lineTo(x(aMid + t * 0.56f), y(0.00f))
            lineTo(x(ax0 + aw), y(1.00f))
            lineTo(x(ax0 + aw - t * 1.08f), y(1.00f))
            close()
        }
        path.addPath(aLeftLeg)
        path.addPath(aRightLeg)
        // Crossbar
        path.addRect(x(ax0 + 0.19f), y(0.61f), x(ax0 + aw - 0.19f), y(0.61f + t * 0.90f), Path.Direction.CW)

        // --- Letter X (x: 4.68 .. 5.55) ---
        val xx0 = 4.68f
        val xw = 0.87f
        val xDiag1 = Path().apply {
            moveTo(x(xx0), y(0.00f))
            lineTo(x(xx0 + t * 1.18f), y(0.00f))
            lineTo(x(xx0 + xw), y(1.00f))
            lineTo(x(xx0 + xw - t * 1.18f), y(1.00f))
            close()
        }
        val xDiag2 = Path().apply {
            moveTo(x(xx0 + xw - t * 1.18f), y(0.00f))
            lineTo(x(xx0 + xw), y(0.00f))
            lineTo(x(xx0 + t * 1.18f), y(1.00f))
            lineTo(x(xx0), y(1.00f))
            close()
        }
        path.addPath(xDiag1)
        path.addPath(xDiag2)

        canvas.drawPath(path, paint)
    }

    /**
     * Draws a crisp minimalist Pentax K-1 pointed pentaprism camera emblem inside [bounds].
     */
    fun drawK1PentaprismCrest(canvas: Canvas, bounds: RectF, strokeColor: Int, accentColor: Int) {
        val w = bounds.width()
        val h = bounds.height()
        if (w <= 0f || h <= 0f) return
        val strokeW = (h * 0.085f).coerceAtLeast(1.8f)

        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = strokeColor
            style = Paint.Style.STROKE
            strokeWidth = strokeW
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }

        // Iconic K-1 pointed pentaprism body silhouette
        val body = Path().apply {
            val l = bounds.left + strokeW
            val r = bounds.right - strokeW
            val t = bounds.top + strokeW
            val b = bounds.bottom - strokeW
            val shoulderY = t + (b - t) * 0.34f
            moveTo(l, shoulderY)
            lineTo(l + (r - l) * 0.28f, shoulderY)
            lineTo(l + (r - l) * 0.42f, t)
            lineTo(l + (r - l) * 0.58f, t)
            lineTo(l + (r - l) * 0.72f, shoulderY)
            lineTo(r, shoulderY)
            lineTo(r, b)
            lineTo(l, b)
            close()
        }
        canvas.drawPath(body, linePaint)

        // Lens ring
        val cx = bounds.centerX()
        val cy = bounds.top + h * 0.65f
        val rad = h * 0.21f
        canvas.drawCircle(cx, cy, rad, linePaint)

        // Accent inner lens dot / ring
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            style = Paint.Style.STROKE
            strokeWidth = strokeW * 0.85f
        }
        canvas.drawCircle(cx, cy, rad * 0.52f, dotPaint)
    }
}

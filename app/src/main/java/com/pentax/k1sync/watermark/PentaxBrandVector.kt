package com.pentax.k1sync.watermark

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

object PentaxBrandVector {

    const val WORDMARK_ASPECT = 5.55f

    fun drawPentaxWordmark(
        canvas: Canvas,
        bounds: RectF,
        color: Int,
        shadowRadius: Float = 0f,
        shadowColor: Int = 0
    ) {
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

        val t = 0.23f
        val path = Path()

        // P
        path.addRect(x(0.00f), y(0.00f), x(t), y(1.00f), Path.Direction.CW)
        path.addRect(x(0.00f), y(0.00f), x(0.62f), y(t), Path.Direction.CW)
        path.addRect(x(0.00f), y(0.45f), x(0.62f), y(0.45f + t), Path.Direction.CW)
        path.addPath(Path().apply {
            moveTo(x(0.56f), y(0.00f))
            cubicTo(x(0.84f), y(0.00f), x(0.84f), y(0.45f + t), x(0.56f), y(0.45f + t))
            lineTo(x(0.56f), y(0.45f))
            cubicTo(x(0.60f), y(0.45f), x(0.60f), y(t), x(0.56f), y(t))
            close()
        })

        // E
        val ex = 0.94f
        path.addRect(x(ex), y(0.00f), x(ex + t), y(1.00f), Path.Direction.CW)
        path.addRect(x(ex), y(0.00f), x(ex + 0.76f), y(t), Path.Direction.CW)
        path.addRect(x(ex), y(0.39f), x(ex + 0.66f), y(0.39f + t * 0.94f), Path.Direction.CW)
        path.addRect(x(ex), y(1.00f - t), x(ex + 0.76f), y(1.00f), Path.Direction.CW)

        // N
        val nx0 = 1.85f
        val nw = 0.86f
        path.addRect(x(nx0), y(0.00f), x(nx0 + t), y(1.00f), Path.Direction.CW)
        path.addRect(x(nx0 + nw - t), y(0.00f), x(nx0 + nw), y(1.00f), Path.Direction.CW)
        path.addPath(Path().apply {
            moveTo(x(nx0), y(0.00f))
            lineTo(x(nx0 + t * 1.15f), y(0.00f))
            lineTo(x(nx0 + nw), y(1.00f))
            lineTo(x(nx0 + nw - t * 1.15f), y(1.00f))
            close()
        })

        // T
        val tx0 = 2.84f
        val tw = 0.82f
        path.addRect(x(tx0), y(0.00f), x(tx0 + tw), y(t), Path.Direction.CW)
        val tStemL = tx0 + (tw - t) / 2f
        path.addRect(x(tStemL), y(0.00f), x(tStemL + t), y(1.00f), Path.Direction.CW)

        // A
        val ax0 = 3.68f
        val aw = 0.94f
        val aMid = ax0 + aw / 2f
        path.addPath(Path().apply {
            moveTo(x(aMid - t * 0.56f), y(0.00f))
            lineTo(x(aMid + t * 0.56f), y(0.00f))
            lineTo(x(ax0 + t * 1.08f), y(1.00f))
            lineTo(x(ax0), y(1.00f))
            close()
        })
        path.addPath(Path().apply {
            moveTo(x(aMid - t * 0.56f), y(0.00f))
            lineTo(x(aMid + t * 0.56f), y(0.00f))
            lineTo(x(ax0 + aw), y(1.00f))
            lineTo(x(ax0 + aw - t * 1.08f), y(1.00f))
            close()
        })
        path.addRect(x(ax0 + 0.19f), y(0.61f), x(ax0 + aw - 0.19f), y(0.61f + t * 0.90f), Path.Direction.CW)

        // X
        val xx0 = 4.68f
        val xw = 0.87f
        path.addPath(Path().apply {
            moveTo(x(xx0), y(0.00f))
            lineTo(x(xx0 + t * 1.18f), y(0.00f))
            lineTo(x(xx0 + xw), y(1.00f))
            lineTo(x(xx0 + xw - t * 1.18f), y(1.00f))
            close()
        })
        path.addPath(Path().apply {
            moveTo(x(xx0 + xw - t * 1.18f), y(0.00f))
            lineTo(x(xx0 + xw), y(0.00f))
            lineTo(x(xx0 + t * 1.18f), y(1.00f))
            lineTo(x(xx0), y(1.00f))
            close()
        })

        canvas.drawPath(path, paint)
    }
}

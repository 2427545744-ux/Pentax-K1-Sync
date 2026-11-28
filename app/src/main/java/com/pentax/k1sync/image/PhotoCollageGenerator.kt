package com.pentax.k1sync.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

object PhotoCollageGenerator {

    suspend fun renderCinemaTriptych(
        photos: List<Bitmap>,
        titleText: String = "PENTAX K-1 · CINEMA STORY",
        subtitleText: String = ""
    ): Bitmap = withContext(Dispatchers.Default) {
        val frames = photos.take(4)
        require(frames.isNotEmpty()) { "至少需要 1 张照片" }

        val canvasW = 2160
        val sidePad = 96
        val topPad = 110
        val gap = 36
        val footerH = 190
        val frameW = canvasW - sidePad * 2
        val frameH = (frameW / 2.35f).roundToInt()

        val canvasH = topPad + frames.size * frameH + (frames.size - 1) * gap + footerH
        val out = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.rgb(12, 12, 14))

        val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.argb(55, 255, 255, 255)
        }

        frames.forEachIndexed { idx, src ->
            val top = topPad + idx * (frameH + gap)
            val dstRect = RectF(sidePad.toFloat(), top.toFloat(), (sidePad + frameW).toFloat(), (top + frameH).toFloat())
            val srcRect = centerCropRect(src.width, src.height, frameW.toFloat() / frameH.toFloat())
            canvas.drawBitmap(src, srcRect, dstRect, bmpPaint)
            canvas.drawRect(dstRect, borderPaint)
        }

        val footerCenterY = canvasH - footerH * 0.48f
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(235, 235, 240)
            textSize = 38f
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
            letterSpacing = 0.22f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(titleText.ifBlank { "PENTAX K-1 · CINEMA STORY" }, canvasW * 0.5f, footerCenterY, titlePaint)

        val dateStr = subtitleText.ifBlank { SimpleDateFormat("yyyy.MM.dd", Locale.US).format(Date()) }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(142, 142, 154)
            textSize = 24f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            letterSpacing = 0.16f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(dateStr, canvasW * 0.5f, footerCenterY + 46f, subPaint)
        out
    }

    suspend fun renderContactSheet(
        photos: List<Bitmap>,
        rollLabel: String = "PENTAX K-1 · 35MM CONTACT SHEET",
        fileNames: List<String> = emptyList()
    ): Bitmap = withContext(Dispatchers.Default) {
        val frames = photos.take(24)
        require(frames.isNotEmpty()) { "至少需要 1 张照片" }

        val cols = when {
            frames.size <= 4 -> 2
            frames.size <= 9 -> 3
            else -> 4
        }
        val rows = ceil(frames.size / cols.toDouble()).toInt()

        val canvasW = 2400
        val marginX = 96
        val headerH = 160
        val footerH = 110
        val cellGapX = 34
        val stripOverhead = 74
        val rowGapY = 38

        val cellW = (canvasW - marginX * 2 - cellGapX * (cols - 1)) / cols
        val cellH = (cellW * 2f / 3f).roundToInt()
        val stripH = cellH + stripOverhead * 2

        val canvasH = headerH + rows * stripH + (rows - 1) * rowGapY + footerH
        val out = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.rgb(11, 11, 12))

        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(232, 186, 102)
            textSize = 36f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.12f
        }
        val dateText = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        canvas.drawText(rollLabel, marginX.toFloat(), 96f, headerPaint)

        val rightMetaPaint = Paint(headerPaint).apply {
            color = Color.rgb(152, 152, 162)
            textSize = 26f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText("${frames.size} FRAMES · $dateText", (canvasW - marginX).toFloat(), 96f, rightMetaPaint)

        val stripBgPaint = Paint().apply { color = Color.rgb(19, 18, 17) }
        val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(7, 7, 8) }
        val holeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            color = Color.argb(55, 255, 255, 255)
        }
        val edgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(225, 166, 78)
            textSize = 22f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val fileLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(170, 170, 178)
            textSize = 18f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            textAlign = Paint.Align.RIGHT
        }
        val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        for (r in 0 until rows) {
            val stripTop = headerH + r * (stripH + rowGapY)
            val stripRect = RectF(
                (marginX - 24).toFloat(), stripTop.toFloat(),
                (canvasW - marginX + 24).toFloat(), (stripTop + stripH).toFloat()
            )
            canvas.drawRoundRect(stripRect, 8f, 8f, stripBgPaint)

            val holeW = 28f; val holeH = 20f; val pitch = 56f
            var hx = stripRect.left + 20f
            while (hx + holeW < stripRect.right - 16f) {
                val topHole = RectF(hx, stripTop + 14f, hx + holeW, stripTop + 14f + holeH)
                val botHole = RectF(hx, stripTop + stripH - 14f - holeH, hx + holeW, stripTop + stripH - 14f)
                canvas.drawRoundRect(topHole, 4f, 4f, holePaint)
                canvas.drawRoundRect(topHole, 4f, 4f, holeStroke)
                canvas.drawRoundRect(botHole, 4f, 4f, holePaint)
                canvas.drawRoundRect(botHole, 4f, 4f, holeStroke)
                hx += pitch
            }

            for (c in 0 until cols) {
                val index = r * cols + c
                if (index >= frames.size) break
                val left = marginX + c * (cellW + cellGapX)
                val imgTop = stripTop + stripOverhead
                val dstRect = RectF(left.toFloat(), imgTop.toFloat(), (left + cellW).toFloat(), (imgTop + cellH).toFloat())
                val src = frames[index]
                val srcRect = centerCropRect(src.width, src.height, 1.5f)
                canvas.drawBitmap(src, srcRect, dstRect, bmpPaint)

                val numTag = "${"%02d".format(index + 1)}A"
                canvas.drawText(numTag, left + 4f, imgTop - 12f, edgeTextPaint)
                val fn = fileNames.getOrNull(index).orEmpty().substringBeforeLast('.')
                if (fn.isNotBlank()) {
                    canvas.drawText(fn, (left + cellW - 4).toFloat(), imgTop - 12f, fileLabelPaint)
                }
                edgeTextPaint.textSize = 18f
                canvas.drawText("PENTAX K-1", left + 4f, (imgTop + cellH + 28).toFloat(), edgeTextPaint)
                edgeTextPaint.textSize = 22f
            }
        }

        val footPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(112, 112, 122)
            textSize = 22f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("PENTAX K-1 SYNC · DARKROOM PROOF SHEET", canvasW * 0.5f, canvasH - 42f, footPaint)
        out
    }

    private fun centerCropRect(srcW: Int, srcH: Int, targetRatio: Float): Rect {
        val srcRatio = srcW.toFloat() / srcH.toFloat().coerceAtLeast(1f)
        return if (srcRatio > targetRatio) {
            val cropW = (srcH * targetRatio).roundToInt().coerceIn(1, srcW)
            val left = (srcW - cropW) / 2
            Rect(left, 0, left + cropW, srcH)
        } else {
            val cropH = (srcW / targetRatio).roundToInt().coerceIn(1, srcH)
            val top = (srcH - cropH) / 2
            Rect(0, top, srcW, top + cropH)
        }
    }
}

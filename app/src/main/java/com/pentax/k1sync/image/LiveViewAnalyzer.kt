package com.pentax.k1sync.image

import android.graphics.Bitmap
import com.pentax.k1sync.model.LiveViewAssistConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

data class SmartFocusTarget(
    val xPercent: Int,
    val yPercent: Int,
    val snapped: Boolean,
    val lowContrastWarning: Boolean,
    val peakGradient: Int
)

object LiveViewAnalyzer {

    suspend fun process(frame: Bitmap, config: LiveViewAssistConfig): Bitmap = withContext(Dispatchers.Default) {
        if (frame.isRecycled || (!config.peakingEnabled && !config.zebraEnabled)) return@withContext frame

        val maxDim = 720
        val working = if (frame.width > maxDim || frame.height > maxDim) {
            val scale = maxDim.toFloat() / maxOf(frame.width, frame.height)
            Bitmap.createScaledBitmap(
                frame,
                (frame.width * scale).toInt().coerceAtLeast(2),
                (frame.height * scale).toInt().coerceAtLeast(2),
                false
            )
        } else {
            frame
        }

        val w = working.width
        val h = working.height
        val count = w * h
        val pixels = IntArray(count)
        working.getPixels(pixels, 0, w, 0, 0, w, h)
        if (working !== frame) working.recycle()

        val luma = IntArray(count)
        val zebraThresh = (config.zebraThresholdPercent.coerceIn(75, 100) * 255) / 100
        val zebraOn = config.zebraEnabled
        val peakOn = config.peakingEnabled
        val peakThresh = config.peakingSensitivity.gradientThreshold
        val peakColor = config.peakingColor.argb

        for (y in 0 until h) {
            val rowOffset = y * w
            for (x in 0 until w) {
                val idx = rowOffset + x
                val px = pixels[idx]
                val r = (px ushr 16) and 0xFF
                val g = (px ushr 8) and 0xFF
                val b = px and 0xFF
                val yVal = (r * 77 + g * 150 + b * 29) ushr 8
                luma[idx] = yVal

                if (zebraOn && (r >= zebraThresh || g >= zebraThresh || b >= zebraThresh)) {
                    val stripe = ((x + y) ushr 2) and 1
                    pixels[idx] = if (stripe == 0) 0xFF141418.toInt() else 0xFFF8F8FA.toInt()
                }
            }
        }

        if (peakOn && w > 2 && h > 2) {
            for (y in 1 until h - 1) {
                val row = y * w
                val rowUp = (y - 1) * w
                val rowDn = (y + 1) * w
                for (x in 1 until w - 1) {
                    val idx = row + x
                    val gx = abs(luma[idx + 1] - luma[idx - 1])
                    val gy = abs(luma[rowDn + x] - luma[rowUp + x])
                    if (gx + gy >= peakThresh) {
                        pixels[idx] = peakColor
                    }
                }
            }
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        out
    }

    /**
     * Scans a ~3.5% neighborhood around the user's tap on the Live View frame and snaps the AF target
     * to the strongest nearby luminance edge so Pentax K-1's Contrast-Detect AF locks cleanly with less hunting.
     */
    suspend fun findSmartFocusPoint(
        frame: Bitmap?,
        tapXFraction: Float,
        tapYFraction: Float,
        enableSnap: Boolean
    ): SmartFocusTarget = withContext(Dispatchers.Default) {
        val rawX = (tapXFraction * 100f).roundToInt().coerceIn(5, 95)
        val rawY = (tapYFraction * 100f).roundToInt().coerceIn(5, 95)
        if (!enableSnap || frame == null || frame.isRecycled || frame.width < 32 || frame.height < 32) {
            return@withContext SmartFocusTarget(rawX, rawY, snapped = false, lowContrastWarning = false, peakGradient = 100)
        }

        val w = frame.width
        val h = frame.height
        val cx = (tapXFraction * w).roundToInt().coerceIn(2, w - 3)
        val cy = (tapYFraction * h).roundToInt().coerceIn(2, h - 3)
        val radius = (minOf(w, h) * 0.042f).roundToInt().coerceIn(8, 36)

        val x0 = (cx - radius).coerceAtLeast(1)
        val y0 = (cy - radius).coerceAtLeast(1)
        val x1 = (cx + radius).coerceAtMost(w - 2)
        val y1 = (cy + radius).coerceAtMost(h - 2)

        val patchW = (x1 - x0 + 3).coerceAtMost(w - (x0 - 1))
        val patchH = (y1 - y0 + 3).coerceAtMost(h - (y0 - 1))
        if (patchW < 3 || patchH < 3) {
            return@withContext SmartFocusTarget(rawX, rawY, snapped = false, lowContrastWarning = false, peakGradient = 100)
        }

        val px = IntArray(patchW * patchH)
        frame.getPixels(px, 0, patchW, x0 - 1, y0 - 1, patchW, patchH)

        fun lumaAt(lx: Int, ly: Int): Int {
            val c = px[ly * patchW + lx]
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            return (r * 77 + g * 150 + b * 29) ushr 8
        }

        var bestScore = -1f
        var bestGrad = 0
        var bestX = cx
        var bestY = cy
        val maxDistSq = (radius * radius).toFloat().coerceAtLeast(1f)

        for (ly in 1 until patchH - 1) {
            val gy = (y0 - 1) + ly
            for (lx in 1 until patchW - 1) {
                val gx = (x0 - 1) + lx
                val dx = (gx - cx).toFloat()
                val dy = (gy - cy).toFloat()
                val distSq = dx * dx + dy * dy
                if (distSq > maxDistSq) continue

                val gradX = abs(lumaAt(lx + 1, ly) - lumaAt(lx - 1, ly))
                val gradY = abs(lumaAt(lx, ly + 1) - lumaAt(lx, ly - 1))
                val grad = gradX + gradY

                // Slight proximity preference so equidistant edges stay faithful to user's finger
                val weight = 1f - 0.30f * (distSq / maxDistSq)
                val score = grad * weight
                if (score > bestScore) {
                    bestScore = score
                    bestGrad = grad
                    bestX = gx
                    bestY = gy
                }
            }
        }

        val lowContrast = bestGrad < 20
        val finalX = if (bestGrad >= 24) ((bestX.toFloat() / w) * 100f).roundToInt().coerceIn(5, 95) else rawX
        val finalY = if (bestGrad >= 24) ((bestY.toFloat() / h) * 100f).roundToInt().coerceIn(5, 95) else rawY
        val didSnap = (finalX != rawX || finalY != rawY) && bestGrad >= 24

        SmartFocusTarget(
            xPercent = finalX,
            yPercent = finalY,
            snapped = didSnap,
            lowContrastWarning = lowContrast,
            peakGradient = bestGrad
        )
    }
}

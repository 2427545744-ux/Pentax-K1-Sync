package com.pentax.k1sync.image

import android.graphics.Bitmap
import com.pentax.k1sync.model.LiveViewAssistConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Real-time Live View optical assist engine for Pentax K-1:
 * 1. Focus Peaking: fast horizontal + vertical luminance gradient edge highlighter (ideal for K/M/A/M42 manual lenses).
 * 2. Highlight Zebra Stripes: 45-degree diagonal broadcast warning bands for pixels exceeding luminance threshold.
 */
object LiveViewAnalyzer {

    suspend fun process(frame: Bitmap, config: LiveViewAssistConfig): Bitmap = withContext(Dispatchers.Default) {
        if (frame.isRecycled || (!config.peakingEnabled && !config.zebraEnabled)) return@withContext frame

        // Cap working resolution to 720px long edge so real-time analysis stays silky smooth (~15-30fps)
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

        // Pass 1: Compute fast integer luminance (and highlight zebra overlay if enabled)
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
                    // Classic 45-degree alternating diagonal zebra stripes
                    val stripe = ((x + y) ushr 2) and 1
                    pixels[idx] = if (stripe == 0) 0xFF141418.toInt() else 0xFFF8F8FA.toInt()
                }
            }
        }

        // Pass 2: Focus peaking sharp-edge detection (Sobel-like 4-neighbor gradient)
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
}

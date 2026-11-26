package com.pentax.k1sync.util

import android.graphics.Bitmap
import com.pentax.k1sync.model.HistogramData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

object HistogramCalculator {

    suspend fun compute(bitmap: Bitmap, bins: Int = 64): HistogramData = withContext(Dispatchers.Default) {
        val sampleW = 160
        val sampleH = (bitmap.height.toFloat() / bitmap.width.coerceAtLeast(1) * sampleW)
            .toInt().coerceIn(60, 220)
        val scaled = Bitmap.createScaledBitmap(bitmap, sampleW, sampleH, false)
        val pixels = IntArray(sampleW * sampleH)
        scaled.getPixels(pixels, 0, sampleW, 0, 0, sampleW, sampleH)
        if (scaled != bitmap) scaled.recycle()

        val luma = IntArray(bins)
        val red = IntArray(bins)
        val green = IntArray(bins)
        val blue = IntArray(bins)
        val scale = (bins - 1) / 255f

        for (px in pixels) {
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            val y = (0.299f * r + 0.587f * g + 0.114f * b).toInt().coerceIn(0, 255)
            luma[(y * scale).toInt()]++
            red[(r * scale).toInt()]++
            green[(g * scale).toInt()]++
            blue[(b * scale).toInt()]++
        }

        val maxVal = max(
            max(luma.maxOrNull() ?: 1, red.maxOrNull() ?: 1),
            max(green.maxOrNull() ?: 1, blue.maxOrNull() ?: 1)
        ).coerceAtLeast(1).toFloat()

        HistogramData(
            luma = FloatArray(bins) { luma[it] / maxVal },
            red = FloatArray(bins) { red[it] / maxVal },
            green = FloatArray(bins) { green[it] / maxVal },
            blue = FloatArray(bins) { blue[it] / maxVal }
        )
    }
}

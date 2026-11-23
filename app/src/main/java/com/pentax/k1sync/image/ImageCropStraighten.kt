package com.pentax.k1sync.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

enum class CropAspectRatio(val label: String, val ratio: Float?) {
    ORIGINAL("原比例", null),
    R_3_2("3:2", 3f / 2f),
    R_4_3("4:3", 4f / 3f),
    R_1_1("1:1", 1f),
    R_4_5("4:5", 4f / 5f),
    R_3_4("3:4", 3f / 4f),
    R_16_9("16:9", 16f / 9f),
    R_235("2.35:1", 2.35f)
}

data class CropStraightenConfig(
    val aspectRatio: CropAspectRatio = CropAspectRatio.ORIGINAL,
    val straightenDegrees: Float = 0f, // -15f .. +15f
    val quarterTurns: Int = 0          // 0, 1, 2, 3 (each = 90 deg CW)
) {
    val isIdentity: Boolean
        get() = aspectRatio == CropAspectRatio.ORIGINAL &&
            abs(straightenDegrees) < 0.05f &&
            (quarterTurns % 4) == 0
}

/**
 * Exact inscribed-rectangle crop and horizon straighten processor.
 * Mathematically guarantees zero black corners when rotating the horizon for any aspect ratio.
 */
object ImageCropStraighten {

    suspend fun apply(source: Bitmap, config: CropStraightenConfig): Bitmap = withContext(Dispatchers.Default) {
        if (config.isIdentity) return@withContext source

        // Step 1: Apply exact 90-degree quarter turns if requested
        val turns = ((config.quarterTurns % 4) + 4) % 4
        val base = if (turns != 0) {
            val m = Matrix().apply { postRotate(turns * 90f) }
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, m, true)
        } else {
            source
        }

        val w = base.width.toFloat()
        val h = base.height.toFloat()
        val angleDeg = config.straightenDegrees.coerceIn(-15f, 15f)
        val rad = Math.toRadians(abs(angleDeg).toDouble())
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()

        val targetRatio = config.aspectRatio.ratio ?: (w / h.coerceAtLeast(1f))

        // Largest centered rectangle of aspect `targetRatio` inscribed strictly inside rotated W x H
        val maxHByWidth = w / (targetRatio * cosA + sinA)
        val maxHByHeight = h / (targetRatio * sinA + cosA)
        val cropH = min(maxHByWidth, maxHByHeight).coerceAtLeast(16f)
        val cropW = (cropH * targetRatio).coerceAtLeast(16f)

        val outW = cropW.roundToInt().coerceAtLeast(16)
        val outH = cropH.roundToInt().coerceAtLeast(16)

        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val matrix = Matrix().apply {
            postTranslate(-w / 2f, -h / 2f)
            postRotate(angleDeg)
            postTranslate(outW / 2f, outH / 2f)
        }
        canvas.drawBitmap(base, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))

        if (base !== source) base.recycle()
        out
    }
}

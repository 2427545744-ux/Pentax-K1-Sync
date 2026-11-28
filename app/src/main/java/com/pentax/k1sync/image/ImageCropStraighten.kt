package com.pentax.k1sync.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
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
    val quarterTurns: Int = 0,         // 0, 1, 2, 3 (each = 90 deg CW)
    val cropZoom: Float = 1f,          // 1f .. 4f zoom inside crop box
    val panX: Float = 0f,              // -1f .. +1f (photo shift right/left within safe bounds)
    val panY: Float = 0f               // -1f .. +1f (photo shift down/up within safe bounds)
) {
    val isIdentity: Boolean
        get() = aspectRatio == CropAspectRatio.ORIGINAL &&
            abs(straightenDegrees) < 0.05f &&
            (quarterTurns % 4) == 0 &&
            cropZoom <= 1.005f &&
            abs(panX) < 0.005f &&
            abs(panY) < 0.005f
}

data class CropGeometry(
    val baseW: Float,
    val baseH: Float,
    val cropW: Float,
    val cropH: Float,
    val maxShiftX: Float,
    val maxShiftY: Float,
    val angleDeg: Float
)

/**
 * Exact inscribed-rectangle crop, pan, zoom, and horizon straighten processor.
 * Shared mathematically by both interactive 60fps Compose crop dragging and full-resolution Bitmap export,
 * guaranteeing 100% WYSIWYG framing and zero black corners at any angle/aspect/zoom/pan.
 */
object ImageCropStraighten {

    fun computeGeometry(rawWidth: Int, rawHeight: Int, config: CropStraightenConfig): CropGeometry {
        val turns = ((config.quarterTurns % 4) + 4) % 4
        val w = (if (turns % 2 == 1) rawHeight else rawWidth).toFloat().coerceAtLeast(16f)
        val h = (if (turns % 2 == 1) rawWidth else rawHeight).toFloat().coerceAtLeast(16f)
        val angleDeg = config.straightenDegrees.coerceIn(-15f, 15f)
        val rad = Math.toRadians(abs(angleDeg).toDouble())
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()

        val targetRatio = config.aspectRatio.ratio ?: (w / h)
        val maxHByWidth = w / (targetRatio * cosA + sinA)
        val maxHByHeight = h / (targetRatio * sinA + cosA)
        val maxCropH = min(maxHByWidth, maxHByHeight).coerceAtLeast(16f)
        val maxCropW = (maxCropH * targetRatio).coerceAtLeast(16f)

        val zoom = config.cropZoom.coerceIn(1f, 4f)
        val cropW = (maxCropW / zoom).coerceAtLeast(16f)
        val cropH = (maxCropH / zoom).coerceAtLeast(16f)

        val halfExtX = (cropW * cosA + cropH * sinA) * 0.5f
        val halfExtY = (cropW * sinA + cropH * cosA) * 0.5f
        val maxShiftX = max(0f, w * 0.5f - halfExtX)
        val maxShiftY = max(0f, h * 0.5f - halfExtY)

        return CropGeometry(
            baseW = w,
            baseH = h,
            cropW = cropW,
            cropH = cropH,
            maxShiftX = maxShiftX,
            maxShiftY = maxShiftY,
            angleDeg = angleDeg
        )
    }

    suspend fun apply(source: Bitmap, config: CropStraightenConfig): Bitmap = withContext(Dispatchers.Default) {
        if (config.isIdentity) return@withContext source

        val turns = ((config.quarterTurns % 4) + 4) % 4
        val base = if (turns != 0) {
            val m = Matrix().apply { postRotate(turns * 90f) }
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, m, true)
        } else {
            source
        }

        val g = computeGeometry(source.width, source.height, config)
        val px = config.panX.coerceIn(-1f, 1f)
        val py = config.panY.coerceIn(-1f, 1f)
        val srcCx = g.baseW * 0.5f - px * g.maxShiftX
        val srcCy = g.baseH * 0.5f - py * g.maxShiftY

        val outW = g.cropW.roundToInt().coerceAtLeast(16)
        val outH = g.cropH.roundToInt().coerceAtLeast(16)

        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val matrix = Matrix().apply {
            postTranslate(-srcCx, -srcCy)
            postRotate(g.angleDeg)
            postTranslate(outW * 0.5f, outH * 0.5f)
        }
        canvas.drawBitmap(base, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))

        if (base !== source) base.recycle()
        out
    }
}

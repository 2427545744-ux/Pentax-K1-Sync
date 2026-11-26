package com.pentax.k1sync.watermark

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 7 Pentax K-1 watermark templates with 100% consistent accent color mapping and official vector PENTAX wordmark.
 */
object PentaxWatermarkRenderer {

    suspend fun render(
        source: Bitmap,
        config: WatermarkConfig,
        maxLongEdge: Int? = null
    ): Bitmap = withContext(Dispatchers.Default) {
        val working = if (maxLongEdge != null && (source.width > maxLongEdge || source.height > maxLongEdge)) {
            val scale = maxLongEdge.toFloat() / maxOf(source.width, source.height)
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).roundToInt().coerceAtLeast(1),
                (source.height * scale).roundToInt().coerceAtLeast(1),
                true
            )
        } else {
            source
        }

        val result = when (config.style) {
            WatermarkStyle.CLASSIC_DIVIDER -> renderClassicDivider(working, config, darkTheme = false)
            WatermarkStyle.DARKROOM_BLACK -> renderClassicDivider(working, config, darkTheme = true)
            WatermarkStyle.MINIMAL_WHITE -> renderMinimalWhite(working, config)
            WatermarkStyle.BLUR_FRAME -> renderBlurFrame(working, config)
            WatermarkStyle.GALLERY_POLAROID -> renderGalleryPolaroid(working, config)
            WatermarkStyle.FILM_35MM -> renderFilm35mm(working, config)
            WatermarkStyle.OVERLAY_MINIMAL -> renderOverlayMinimal(working, config)
        }

        if (working !== source) working.recycle()
        result
    }


    private fun drawTextFitWidth(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        paint: Paint,
        maxWidth: Float
    ) {
        if (text.isBlank() || maxWidth <= 8f) return
        val measured = paint.measureText(text)
        if (measured <= maxWidth) {
            canvas.drawText(text, x, y, paint)
        } else {
            val scaledPaint = Paint(paint).apply {
                textSize = (paint.textSize * (maxWidth / measured)).coerceAtLeast(paint.textSize * 0.58f)
            }
            var finalStr = text
            while (finalStr.length > 3 && scaledPaint.measureText("$finalStr…") > maxWidth) {
                finalStr = finalStr.dropLast(1)
            }
            val display = if (finalStr.length < text.length) "$finalStr…" else finalStr
            canvas.drawText(display, x, y, scaledPaint)
        }
    }

    private fun buildSubInfoLine(cfg: WatermarkConfig): String = buildString {
        if (cfg.showDateTime && cfg.dateTime.isNotBlank()) append(cfg.dateTime)
        if (cfg.signature.isNotBlank()) {
            if (isNotEmpty()) append("  ·  ")
            append(cfg.signature)
        }
    }

    /**
     * Templates 1 & 5: Classic Dual-Column Divider (Light White OR Darkroom Obsidian Black)
     */
    private fun renderClassicDivider(src: Bitmap, cfg: WatermarkConfig, darkTheme: Boolean): Bitmap {
        val w = src.width
        val h = src.height
        val shortEdge = min(w, h).toFloat()
        val barH = (shortEdge * 0.148f).roundToInt().coerceAtLeast(84)

        val bgColor = if (darkTheme) Color.rgb(15, 15, 18) else Color.WHITE
        val primaryText = if (darkTheme) Color.rgb(242, 242, 246) else Color.rgb(20, 20, 22)
        val secondaryText = if (darkTheme) Color.rgb(154, 154, 164) else Color.rgb(134, 134, 142)
        val brandColor = cfg.resolveAccentForSurface(isDarkSurface = darkTheme)
        val dividerColor = if (darkTheme) Color.rgb(58, 58, 66) else Color.rgb(214, 214, 218)

        val out = Bitmap.createBitmap(w, h + barH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(bgColor)
        canvas.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))

        if (darkTheme) {
            val lineP = Paint().apply {
                color = if (cfg.accentColor == PentaxAccentColor.CHAMPAGNE_GOLD) {
                    Color.argb(120, 212, 168, 76)
                } else {
                    Color.rgb(38, 38, 44)
                }
                strokeWidth = (shortEdge * 0.0022f).coerceAtLeast(1.2f)
            }
            canvas.drawLine(0f, h.toFloat(), w.toFloat(), h.toFloat(), lineP)
        }

        val padX = w * 0.046f
        val barCenterY = h + barH / 2f

        val portraitScale = if (h > w) 0.84f else 1.0f
        val exifPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = primaryText
            textSize = barH * 0.205f * portraitScale
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.015f
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = secondaryText
            textSize = barH * 0.155f * portraitScale
            letterSpacing = 0.02f
        }

        val exifStr = cfg.buildExifLine(uppercaseF = false)
        val subStr = buildSubInfoLine(cfg)
        val rightBlockW = maxOf(
            exifPaint.measureText(exifStr),
            if (subStr.isNotBlank()) subPaint.measureText(subStr) else 0f
        )
        val rightBlockLeft = (w - padX - rightBlockW).coerceAtLeast(w * 0.56f)
        val maxRightW = (w - padX - rightBlockLeft).coerceAtLeast(w * 0.25f)

        if (subStr.isNotBlank() && exifStr.isNotBlank()) {
            drawTextFitWidth(canvas, exifStr, rightBlockLeft, barCenterY - barH * 0.04f, exifPaint, maxRightW)
            drawTextFitWidth(canvas, subStr, rightBlockLeft, barCenterY + barH * 0.22f, subPaint, maxRightW)
        } else {
            val onlyText = exifStr.ifBlank { subStr }
            val p = if (exifStr.isNotBlank()) exifPaint else subPaint
            val baseY = barCenterY - (p.descent() + p.ascent()) / 2f
            drawTextFitWidth(canvas, onlyText, rightBlockLeft, baseY, p, maxRightW)
        }

        val divX = rightBlockLeft - barH * 0.19f * portraitScale
        val divPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = dividerColor
            strokeWidth = (barH * 0.014f).coerceAtLeast(1.5f)
        }
        canvas.drawLine(divX, barCenterY - barH * 0.25f, divX, barCenterY + barH * 0.25f, divPaint)

        val logoH = barH * 0.21f * portraitScale
        val logoW = logoH * PentaxBrandVector.WORDMARK_ASPECT
        val logoRight = divX - barH * 0.19f * portraitScale
        val logoLeft = logoRight - logoW
        PentaxBrandVector.drawPentaxWordmark(
            canvas,
            RectF(logoLeft, barCenterY - logoH / 2f, logoRight, barCenterY + logoH / 2f),
            brandColor
        )

        val hasLens = cfg.showLensName && cfg.lensTitle.isNotBlank()
        val maxLeftW = (logoLeft - padX - barH * 0.14f).coerceAtLeast(w * 0.18f)
        val camPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = primaryText
            textSize = barH * 0.24f * portraitScale
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.04f
        }
        val modelStr = cfg.cameraTitle.ifBlank { "PENTAX K-1" }
        if (hasLens) {
            drawTextFitWidth(canvas, modelStr, padX, barCenterY - barH * 0.04f, camPaint, maxLeftW)
            val lensPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = secondaryText
                textSize = barH * 0.145f * portraitScale
            }
            drawTextFitWidth(canvas, cfg.lensTitle, padX, barCenterY + barH * 0.22f, lensPaint, maxLeftW)
        } else {
            val baseY = barCenterY - (camPaint.descent() + camPaint.ascent()) / 2f
            drawTextFitWidth(canvas, modelStr, padX, baseY, camPaint, maxLeftW)
        }

        return out
    }

    /**
     * Template 2: Minimal White Border — colored official PENTAX vector wordmark + model + accent square
     */
    private fun renderMinimalWhite(src: Bitmap, cfg: WatermarkConfig): Bitmap {
        val w = src.width
        val h = src.height
        val shortEdge = min(w, h).toFloat()
        val barH = (shortEdge * 0.132f).roundToInt().coerceAtLeast(72)

        val out = Bitmap.createBitmap(w, h + barH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))

        if (cfg.showOverlaySignature && cfg.signature.isNotBlank()) {
            val ovPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(232, 255, 255, 255)
                textSize = shortEdge * 0.024f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
                setShadowLayer(shortEdge * 0.008f, 0f, shortEdge * 0.003f, Color.argb(180, 0, 0, 0))
            }
            canvas.drawText("${cfg.cameraTitle}  ·  ${cfg.signature}", w / 2f, h - shortEdge * 0.028f, ovPaint)
        }

        val padX = w * 0.048f
        val barCenterY = h + barH / 2f
        val brandColor = cfg.resolveAccentForSurface(isDarkSurface = false)
        val hasLens = cfg.showLensName && cfg.lensTitle.isNotBlank()

        val logoH = barH * 0.215f
        val logoW = logoH * PentaxBrandVector.WORDMARK_ASPECT
        val logoTop = if (hasLens) barCenterY - barH * 0.22f else barCenterY - logoH / 2f
        PentaxBrandVector.drawPentaxWordmark(
            canvas,
            RectF(padX, logoTop, padX + logoW, logoTop + logoH),
            brandColor
        )

        val modelSuffix = cfg.cameraTitle.replace("PENTAX", "", ignoreCase = true).trim()
        var cursorX = padX + logoW + barH * 0.12f
        if (modelSuffix.isNotBlank()) {
            val sufPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(26, 26, 28)
                textSize = barH * 0.255f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                letterSpacing = 0.04f
            }
            val sufY = (logoTop + logoH / 2f) - (sufPaint.descent() + sufPaint.ascent()) / 2f
            canvas.drawText(modelSuffix, cursorX, sufY, sufPaint)
            cursorX += sufPaint.measureText(modelSuffix) + barH * 0.11f
        }

        val sqSize = barH * 0.11f
        val sqBottom = logoTop + logoH
        val sqPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = brandColor }
        canvas.drawRoundRect(
            RectF(cursorX, sqBottom - sqSize, cursorX + sqSize, sqBottom),
            sqSize * 0.18f, sqSize * 0.18f, sqPaint
        )

        if (hasLens) {
            val lensPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(130, 130, 136)
                textSize = barH * 0.145f
            }
            canvas.drawText(cfg.lensTitle, padX, barCenterY + barH * 0.26f, lensPaint)
        }

        val exifPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(26, 26, 28)
            textSize = barH * 0.22f
            textAlign = Paint.Align.RIGHT
            letterSpacing = 0.015f
        }
        val exifLine = cfg.buildExifLine(uppercaseF = false)
        val subLine = buildSubInfoLine(cfg)
        if (subLine.isNotBlank() && exifLine.isNotBlank()) {
            canvas.drawText(exifLine, w - padX, barCenterY - barH * 0.03f, exifPaint)
            val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(142, 142, 148)
                textSize = barH * 0.145f
                textAlign = Paint.Align.RIGHT
            }
            canvas.drawText(subLine, w - padX, barCenterY + barH * 0.23f, datePaint)
        } else {
            val baseY = barCenterY - (exifPaint.descent() + exifPaint.ascent()) / 2f
            canvas.drawText(exifLine.ifBlank { subLine }, w - padX, baseY, exifPaint)
        }

        return out
    }

    /**
     * Template 3: Ambient Blurred Frame — colored PENTAX emblem + crisp white EXIF
     */
    private fun renderBlurFrame(src: Bitmap, cfg: WatermarkConfig): Bitmap {
        val w = src.width
        val h = src.height
        val shortEdge = min(w, h).toFloat()

        val padX = (shortEdge * 0.088f).roundToInt()
        val padTop = (shortEdge * 0.072f).roundToInt()
        val padBottom = (shortEdge * 0.185f).roundToInt().coerceAtLeast(96)

        val outW = w + padX * 2
        val outH = h + padTop + padBottom
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        val blurThumbW = 240
        val blurThumbH = (blurThumbW * (outH.toFloat() / outW)).roundToInt().coerceIn(120, 420)
        val tiny = Bitmap.createScaledBitmap(src, blurThumbW, blurThumbH, true)
        val blurred = FastStackBlur.blur(tiny, radius = 26)
        tiny.recycle()
        canvas.drawBitmap(blurred, null, Rect(0, 0, outW, outH), Paint(Paint.FILTER_BITMAP_FLAG))
        blurred.recycle()

        val scrim = Paint().apply {
            shader = LinearGradient(
                0f, outH * 0.56f, 0f, outH.toFloat(),
                intArrayOf(Color.TRANSPARENT, Color.argb(155, 6, 8, 12)),
                null, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, outW.toFloat(), outH.toFloat(), scrim)

        val photoRect = RectF(padX.toFloat(), padTop.toFloat(), (padX + w).toFloat(), (padTop + h).toFloat())
        val cornerR = shortEdge * 0.025f
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(235, 10, 10, 12)
            setShadowLayer(shortEdge * 0.038f, 0f, shortEdge * 0.016f, Color.argb(195, 0, 0, 0))
        }
        canvas.drawRoundRect(photoRect, cornerR, cornerR, shadowPaint)

        val saved = canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(photoRect, cornerR, cornerR, Path.Direction.CW) })
        canvas.drawBitmap(src, photoRect.left, photoRect.top, Paint(Paint.FILTER_BITMAP_FLAG))
        canvas.restoreToCount(saved)

        val footerTop = padTop + h
        val centerX = outW / 2f
        val brandColor = cfg.resolveAccentForSurface(isDarkSurface = true)

        val logoH = padBottom * 0.21f
        val logoW = logoH * PentaxBrandVector.WORDMARK_ASPECT
        val modelSuffix = cfg.cameraTitle.replace("PENTAX", "", ignoreCase = true).trim()
        val modelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(242, 255, 255, 255)
            textSize = padBottom * 0.22f
            letterSpacing = 0.06f
            setShadowLayer(padBottom * 0.05f, 0f, padBottom * 0.02f, Color.argb(150, 0, 0, 0))
        }
        val gap = if (modelSuffix.isNotBlank()) padBottom * 0.13f else 0f
        val suffixW = if (modelSuffix.isNotBlank()) modelPaint.measureText(modelSuffix) else 0f
        val totalW = logoW + gap + suffixW
        val startX = centerX - totalW / 2f
        val logoTop = footerTop + padBottom * 0.28f

        PentaxBrandVector.drawPentaxWordmark(
            canvas,
            RectF(startX, logoTop, startX + logoW, logoTop + logoH),
            brandColor,
            shadowRadius = padBottom * 0.05f,
            shadowColor = Color.argb(165, 0, 0, 0)
        )
        if (modelSuffix.isNotBlank()) {
            val sufY = (logoTop + logoH / 2f) - (modelPaint.descent() + modelPaint.ascent()) / 2f
            canvas.drawText(modelSuffix, startX + logoW + gap, sufY, modelPaint)
        }

        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = brandColor
            strokeWidth = (padBottom * 0.022f).coerceAtLeast(2f)
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(startX, logoTop + logoH + padBottom * 0.065f, startX + logoW, logoTop + logoH + padBottom * 0.065f, barPaint)

        val exifPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(225, 242, 245, 248)
            textSize = padBottom * 0.16f
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.03f
            setShadowLayer(padBottom * 0.04f, 0f, padBottom * 0.015f, Color.argb(150, 0, 0, 0))
        }
        val line2 = buildString {
            append(cfg.buildExifLine(uppercaseF = true))
            if (cfg.showLensName && cfg.lensTitle.isNotBlank()) {
                if (isNotEmpty()) append("   ·   ")
                append(cfg.lensTitle)
            } else if (cfg.signature.isNotBlank()) {
                if (isNotEmpty()) append("   ·   ")
                append(cfg.signature)
            }
        }
        canvas.drawText(line2, centerX, footerTop + padBottom * 0.79f, exifPaint)
        return out
    }

    /**
     * Template 4: Museum Gallery Mat Frame
     */
    private fun renderGalleryPolaroid(src: Bitmap, cfg: WatermarkConfig): Bitmap {
        val w = src.width
        val h = src.height
        val shortEdge = min(w, h).toFloat()

        val sidePad = (shortEdge * 0.055f).roundToInt()
        val topPad = (shortEdge * 0.055f).roundToInt()
        val bottomPad = (shortEdge * 0.21f).roundToInt().coerceAtLeast(110)

        val outW = w + sidePad * 2
        val outH = h + topPad + bottomPad
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.rgb(252, 252, 250))

        canvas.drawBitmap(src, sidePad.toFloat(), topPad.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))

        val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(226, 226, 222)
            style = Paint.Style.STROKE
            strokeWidth = (shortEdge * 0.002f).coerceAtLeast(1.2f)
        }
        canvas.drawRect(sidePad.toFloat(), topPad.toFloat(), (sidePad + w).toFloat(), (topPad + h).toFloat(), framePaint)

        val footerTop = topPad + h
        val centerX = outW / 2f
        val brandColor = cfg.resolveAccentForSurface(isDarkSurface = false)
        val logoH = bottomPad * 0.165f
        val logoW = logoH * PentaxBrandVector.WORDMARK_ASPECT
        val logoTop = footerTop + bottomPad * 0.24f

        PentaxBrandVector.drawPentaxWordmark(
            canvas,
            RectF(centerX - logoW / 2f, logoTop, centerX + logoW / 2f, logoTop + logoH),
            brandColor
        )

        val modelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(34, 34, 38)
            textSize = bottomPad * 0.135f
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.09f
        }
        val titleLine = buildString {
            append(cfg.cameraTitle.ifBlank { "PENTAX K-1" })
            if (cfg.showLensName && cfg.lensTitle.isNotBlank()) {
                append("   /   ")
                append(cfg.lensTitle)
            }
        }
        canvas.drawText(titleLine, centerX, footerTop + bottomPad * 0.59f, modelPaint)

        val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(122, 122, 128)
            textSize = bottomPad * 0.118f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.04f
        }
        val metaLine = buildString {
            append(cfg.buildExifLine(uppercaseF = false))
            val sub = buildSubInfoLine(cfg)
            if (sub.isNotBlank()) {
                if (isNotEmpty()) append("   |   ")
                append(sub)
            }
        }
        canvas.drawText(metaLine, centerX, footerTop + bottomPad * 0.79f, metaPaint)

        return out
    }

    /**
     * Template 6: 35mm Classic Film Strip Frame (Respects chosen brand accent color!)
     */
    private fun renderFilm35mm(src: Bitmap, cfg: WatermarkConfig): Bitmap {
        val w = src.width
        val h = src.height
        val shortEdge = min(w, h).toFloat()

        val sidePad = (shortEdge * 0.028f).roundToInt()
        val topFilmH = (shortEdge * 0.105f).roundToInt().coerceAtLeast(54)
        val bottomFilmH = (shortEdge * 0.125f).roundToInt().coerceAtLeast(66)

        val outW = w + sidePad * 2
        val outH = h + topFilmH + bottomFilmH
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.rgb(16, 15, 14))

        canvas.drawBitmap(src, sidePad.toFloat(), topFilmH.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))

        val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(232, 228, 220) }
        val holeW = shortEdge * 0.026f
        val holeH = shortEdge * 0.036f
        val holeR = holeW * 0.22f
        val pitch = holeW * 2.25f
        val topHoleY = topFilmH * 0.18f
        val botHoleY = topFilmH + h + bottomFilmH - holeH - bottomFilmH * 0.14f

        var hx = sidePad.toFloat() + pitch * 0.35f
        while (hx + holeW < outW - sidePad) {
            canvas.drawRoundRect(RectF(hx, topHoleY, hx + holeW, topHoleY + holeH), holeR, holeR, holePaint)
            canvas.drawRoundRect(RectF(hx, botHoleY, hx + holeW, botHoleY + holeH), holeR, holeR, holePaint)
            hx += pitch
        }

        // Respects user's selected color; falls back to classic Kodak film amber if Classic Black is chosen
        val imprintColor = if (cfg.accentColor == PentaxAccentColor.CLASSIC_BLACK) {
            Color.rgb(232, 162, 68)
        } else {
            cfg.accentColor.colorInt
        }
        val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = imprintColor
            textSize = topFilmH * 0.25f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.08f
        }
        val topTextY = topFilmH * 0.84f
        canvas.drawText("▶ 36A   ${cfg.cameraTitle.uppercase()}   FULL FRAME 36MP", sidePad * 1.4f, topTextY, edgePaint)
        edgePaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("PENTAX K-MOUNT ◀", outW - sidePad * 1.4f, topTextY, edgePaint)

        val botTextY = topFilmH + h + bottomFilmH * 0.36f
        val logoH = bottomFilmH * 0.22f
        val logoW = logoH * PentaxBrandVector.WORDMARK_ASPECT
        PentaxBrandVector.drawPentaxWordmark(
            canvas,
            RectF(sidePad * 1.4f, botTextY - logoH * 0.78f, sidePad * 1.4f + logoW, botTextY + logoH * 0.22f),
            imprintColor
        )

        val botMeta = buildString {
            append(cfg.buildExifLine(uppercaseF = true))
            if (cfg.showLensName && cfg.lensTitle.isNotBlank()) {
                if (isNotEmpty()) append("   ·   ")
                append(cfg.lensTitle)
            } else if (cfg.showDateTime && cfg.dateTime.isNotBlank()) {
                if (isNotEmpty()) append("   ·   ")
                append(cfg.dateTime)
            }
        }
        edgePaint.textAlign = Paint.Align.RIGHT
        edgePaint.textSize = bottomFilmH * 0.21f
        canvas.drawText(botMeta, outW - sidePad * 1.4f, botTextY, edgePaint)

        return out
    }

    /**
     * Template 7: Borderless Minimal Corner Overlay (Preserves original aspect ratio)
     */
    private fun renderOverlayMinimal(src: Bitmap, cfg: WatermarkConfig): Bitmap {
        val w = src.width
        val h = src.height
        val shortEdge = min(w, h).toFloat()

        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)

        val scrimH = shortEdge * 0.18f
        val scrimPaint = Paint().apply {
            shader = LinearGradient(
                0f, h - scrimH, 0f, h.toFloat(),
                intArrayOf(Color.TRANSPARENT, Color.argb(175, 0, 0, 0)),
                null, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, h - scrimH, w.toFloat(), h.toFloat(), scrimPaint)

        val padX = w * 0.038f
        val padBottom = shortEdge * 0.036f
        val logoH = shortEdge * 0.029f
        val logoW = logoH * PentaxBrandVector.WORDMARK_ASPECT
        val hasLens = cfg.showLensName && cfg.lensTitle.isNotBlank()
        val brandColor = cfg.resolveAccentForSurface(isDarkSurface = true)

        val logoBottom = if (hasLens) h - padBottom - shortEdge * 0.028f else h - padBottom
        PentaxBrandVector.drawPentaxWordmark(
            canvas,
            RectF(padX, logoBottom - logoH, padX + logoW, logoBottom),
            brandColor,
            shadowRadius = shortEdge * 0.008f,
            shadowColor = Color.argb(185, 0, 0, 0)
        )

        val modelSuffix = cfg.cameraTitle.replace("PENTAX", "", ignoreCase = true).trim()
        if (modelSuffix.isNotBlank()) {
            val sufPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = logoH * 1.15f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                setShadowLayer(shortEdge * 0.008f, 0f, shortEdge * 0.003f, Color.argb(180, 0, 0, 0))
            }
            canvas.drawText(modelSuffix, padX + logoW + shortEdge * 0.015f, logoBottom, sufPaint)
        }

        if (hasLens) {
            val lensPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(215, 235, 235, 240)
                textSize = shortEdge * 0.020f
                setShadowLayer(shortEdge * 0.006f, 0f, shortEdge * 0.002f, Color.argb(180, 0, 0, 0))
            }
            canvas.drawText(cfg.lensTitle, padX, h - padBottom, lensPaint)
        }

        val rightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(242, 255, 255, 255)
            textSize = shortEdge * 0.024f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
            setShadowLayer(shortEdge * 0.008f, 0f, shortEdge * 0.003f, Color.argb(180, 0, 0, 0))
        }
        val exifLine = cfg.buildExifLine(uppercaseF = false)
        val subLine = buildSubInfoLine(cfg)
        if (subLine.isNotBlank() && exifLine.isNotBlank()) {
            canvas.drawText(exifLine, w - padX, h - padBottom - shortEdge * 0.028f, rightPaint)
            val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(205, 225, 225, 230)
                textSize = shortEdge * 0.019f
                textAlign = Paint.Align.RIGHT
                setShadowLayer(shortEdge * 0.006f, 0f, shortEdge * 0.002f, Color.argb(180, 0, 0, 0))
            }
            canvas.drawText(subLine, w - padX, h - padBottom, subPaint)
        } else {
            canvas.drawText(exifLine.ifBlank { subLine }, w - padX, h - padBottom, rightPaint)
        }

        return out
    }
}

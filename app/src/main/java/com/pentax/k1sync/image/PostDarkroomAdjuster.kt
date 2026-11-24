package com.pentax.k1sync.image

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

data class PostToneConfig(
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val skinLuminance: Float = 0f,
    val skinSaturation: Float = 0f
) {
    val isIdentity: Boolean
        get() = abs(highlights) < 0.01f &&
            abs(shadows) < 0.01f &&
            abs(contrast) < 0.01f &&
            abs(saturation) < 0.01f &&
            abs(skinLuminance) < 0.01f &&
            abs(skinSaturation) < 0.01f
}

object PostDarkroomAdjuster {

    suspend fun apply(
        source: Bitmap,
        tone: PostToneConfig,
        showVintageDate: Boolean = false,
        dateStringRaw: String = ""
    ): Bitmap = withContext(Dispatchers.Default) {
        if (tone.isIdentity && !showVintageDate) return@withContext source

        val w = source.width
        val h = source.height
        val out = if (!tone.isIdentity) {
            val pixels = IntArray(w * h)
            source.getPixels(pixels, 0, w, 0, 0, w, h)

            val hi = tone.highlights.coerceIn(-1f, 1f)
            val sh = tone.shadows.coerceIn(-1f, 1f)
            val ct = tone.contrast.coerceIn(-1f, 1f)
            val satMult = (1f + tone.saturation.coerceIn(-1f, 1f) * 0.75f).coerceAtLeast(0f)
            val skinLum = tone.skinLuminance.coerceIn(-1f, 1f)
            val skinSat = tone.skinSaturation.coerceIn(-1f, 1f)
            val doSkin = abs(skinLum) > 0.01f || abs(skinSat) > 0.01f
            val doTone = abs(hi) > 0.01f || abs(sh) > 0.01f || abs(ct) > 0.01f
            val doSat = abs(tone.saturation) > 0.01f

            val toneLut = FloatArray(256)
            for (i in 0..255) {
                var v = i / 255f
                if (abs(sh) > 0.01f) {
                    val mask = (1f - v).pow(2.4f)
                    v += sh * 0.34f * mask * (0.25f + 0.75f * v * 2f).coerceAtMost(1f)
                }
                if (abs(hi) > 0.01f) {
                    val mask = v.pow(2.4f)
                    v += hi * 0.30f * mask * (1.15f - 0.15f * v)
                }
                v = v.coerceIn(0f, 1f)
                if (abs(ct) > 0.01f) {
                    val sCurve = v * v * (3f - 2f * v)
                    v = if (ct > 0f) v + (sCurve - v) * (ct * 0.85f) else v + (0.5f - v) * (-ct * 0.42f)
                }
                toneLut[i] = v.coerceIn(0f, 1f)
            }

            val inv255 = 1f / 255f
            for (idx in pixels.indices) {
                val px = pixels[idx]
                val a = px and 0xFF000000.toInt()
                var r = ((px ushr 16) and 0xFF) * inv255
                var g = ((px ushr 8) and 0xFF) * inv255
                var b = (px and 0xFF) * inv255

                if (doTone) {
                    val lumaIn = (0.299f * r + 0.587f * g + 0.114f * b).coerceIn(0f, 1f)
                    val lumaIdx = (lumaIn * 255f + 0.5f).toInt().coerceIn(0, 255)
                    val lumaOut = toneLut[lumaIdx]
                    if (lumaIn > 0.002f) {
                        val ratio = (lumaOut / lumaIn).coerceIn(0.25f, 3.2f)
                        r = (r * ratio).coerceIn(0f, 1f)
                        g = (g * ratio).coerceIn(0f, 1f)
                        b = (b * ratio).coerceIn(0f, 1f)
                    } else if (lumaOut > lumaIn) {
                        val lift = lumaOut - lumaIn
                        r = (r + lift).coerceIn(0f, 1f)
                        g = (g + lift).coerceIn(0f, 1f)
                        b = (b + lift).coerceIn(0f, 1f)
                    }
                }

                if (doSat) {
                    val luma = 0.299f * r + 0.587f * g + 0.114f * b
                    r = (luma + (r - luma) * satMult).coerceIn(0f, 1f)
                    g = (luma + (g - luma) * satMult).coerceIn(0f, 1f)
                    b = (luma + (b - luma) * satMult).coerceIn(0f, 1f)
                }

                if (doSkin) {
                    val maxC = max(r, max(g, b))
                    val minC = min(r, min(g, b))
                    val delta = maxC - minC
                    if (delta > 0.03f && maxC > 0.12f) {
                        val hueDeg = when (maxC) {
                            r -> 60f * (((g - b) / delta) % 6f)
                            g -> 60f * (((b - r) / delta) + 2f)
                            else -> 60f * (((r - g) / delta) + 4f)
                        }.let { if (it < 0f) it + 360f else it }

                        var dHue = abs(hueDeg - 24f)
                        if (dHue > 180f) dHue = 360f - dHue
                        if (dHue < 32f) {
                            val satC = delta / maxC
                            val satGate = ((satC - 0.08f) / 0.18f).coerceIn(0f, 1f) * (1f - ((satC - 0.78f) / 0.22f).coerceIn(0f, 1f))
                            val hueWeight = ((1f - dHue / 32f).pow(1.6f)) * satGate
                            if (hueWeight > 0.005f) {
                                val lumGain = 1f + skinLum * 0.24f * hueWeight
                                r = (r * lumGain).coerceIn(0f, 1f)
                                g = (g * lumGain).coerceIn(0f, 1f)
                                b = (b * lumGain).coerceIn(0f, 1f)

                                val luma = 0.299f * r + 0.587f * g + 0.114f * b
                                val sMul = (1f + skinSat * 0.55f * hueWeight).coerceAtLeast(0f)
                                r = (luma + (r - luma) * sMul).coerceIn(0f, 1f)
                                g = (luma + (g - luma) * sMul).coerceIn(0f, 1f)
                                b = (luma + (b - luma) * sMul).coerceIn(0f, 1f)
                            }
                        }
                    }
                }

                val ri = (r * 255f + 0.5f).toInt().coerceIn(0, 255)
                val gi = (g * 255f + 0.5f).toInt().coerceIn(0, 255)
                val bi = (b * 255f + 0.5f).toInt().coerceIn(0, 255)
                pixels[idx] = a or (ri shl 16) or (gi shl 8) or bi
            }

            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, w, 0, 0, w, h)
            }
        } else {
            source.copy(Bitmap.Config.ARGB_8888, true)
        }

        if (showVintageDate) {
            drawGlowingVintageDate(out, dateStringRaw)
        }
        out
    }

    private fun drawGlowingVintageDate(bitmap: Bitmap, dateStringRaw: String) {
        val canvas = Canvas(bitmap)
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val shortEdge = min(w, h)
        val stamp = formatQuartzDate(dateStringRaw)

        val textSize = (shortEdge * 0.036f).coerceIn(16f, 260f)
        val x = w - w * 0.048f
        val y = h - h * 0.052f

        val outerGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            this.textSize = textSize
            textAlign = Paint.Align.RIGHT
            letterSpacing = 0.14f
            color = Color.argb(170, 255, 58, 12)
            maskFilter = BlurMaskFilter((textSize * 0.28f).coerceAtLeast(2f), BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawText(stamp, x, y, outerGlow)

        val innerGlow = Paint(outerGlow).apply {
            color = Color.argb(225, 255, 118, 28)
            maskFilter = BlurMaskFilter((textSize * 0.09f).coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawText(stamp, x, y, innerGlow)

        val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            this.textSize = textSize
            textAlign = Paint.Align.RIGHT
            letterSpacing = 0.14f
            color = Color.argb(242, 255, 198, 108)
        }
        canvas.drawText(stamp, x, y, corePaint)
    }

    private fun formatQuartzDate(raw: String): String {
        val m = Regex("""(\d{4})[.:/-](\d{2})[.:/-](\d{2})""").find(raw.trim())
        if (m != null) {
            return "'${m.groupValues[1].takeLast(2)}  ${m.groupValues[2]}  ${m.groupValues[3]}"
        }
        val today = SimpleDateFormat("yy  MM  dd", Locale.US).format(Date())
        return "'$today"
    }
}

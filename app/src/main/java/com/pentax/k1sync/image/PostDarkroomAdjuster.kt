package com.pentax.k1sync.image

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class ColorBalanceZone(val label: String) {
    SHADOWS("阴影"),
    MIDTONES("中间调"),
    HIGHLIGHTS("高光")
}

enum class CurveChannel(val label: String, val accentHex: Long) {
    LUMA("亮度", 0xFFE5E7EB),
    RED("红 R", 0xFFFF5A52),
    GREEN("绿 G", 0xFF34D399),
    BLUE("蓝 B", 0xFF3B82F6)
}

enum class ToneCurvePreset(val label: String, val points: List<Float>) {
    LINEAR("线性归零", listOf(0.0f, 0.25f, 0.50f, 0.75f, 1.0f)),
    MATTE_FADE("胶片褪色", listOf(0.09f, 0.24f, 0.51f, 0.77f, 0.96f)),
    PUNCH_S("电影S反差", listOf(0.0f, 0.18f, 0.50f, 0.83f, 1.0f)),
    SOFT_LIFT("日系柔亮", listOf(0.05f, 0.32f, 0.57f, 0.80f, 0.98f))
}

enum class PsBlendMode(val label: String, val desc: String) {
    NONE("关闭", "不叠加混合层"),
    SOFT_LIGHT("柔光", "柔和氛围+微提立体反差，天然保护纯黑白"),
    COLOR("颜色", "严格锁定原图明度，只注入纯净色相"),
    OVERLAY("叠加", "浓郁杂志对比与高饱和混色"),
    SCREEN("滤色", "日系通透柔雾空气感"),
    MULTIPLY("正片叠底", "深沉电影暗调压光")
}

enum class PsBlendRange(val label: String) {
    ALL_PROTECTED("全图护黑白"),
    SHADOWS("仅暗部"),
    MIDTONES("仅中间调"),
    HIGHLIGHTS("仅高光")
}

data class PsColorSwatch(val label: String, val hueDeg: Float, val argbHex: Long)

val PS_BLEND_SWATCHES = listOf(
    PsColorSwatch("电影青", 198f, 0xFF1EA8C8),
    PsColorSwatch("暖夕橙", 28f, 0xFFE8863A),
    PsColorSwatch("柯达金", 44f, 0xFFE6B645),
    PsColorSwatch("富士绿", 156f, 0xFF2FA37A),
    PsColorSwatch("暮光紫", 272f, 0xFF8B62D9),
    PsColorSwatch("樱花粉", 346f, 0xFFE57896)
)

data class PostToneConfig(
    // 0. Universal White Balance (works on JPG, K-1 previews, and post-darkroom RAW!)
    val temperature: Float = 0f,    // -1f (Cool Blue) .. +1f (Warm Amber)
    val tint: Float = 0f,           // -1f (Green) .. +1f (Magenta)

    // 1. Base Tone, Dehaze & Exposure Shaping
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val contrast: Float = 0f,
    val dehaze: Float = 0f,         // -1f (Dreamy Mist) .. +1f (Crisp Dehaze)
    val vibrance: Float = 0f,
    val saturation: Float = 0f,

    // 1.5 Interactive 5-Point Monotonic Spline Tone Curves (x = [0.0, 0.25, 0.50, 0.75, 1.0])
    val lumaCurve: List<Float> = DEFAULT_CURVE,
    val redCurve: List<Float> = DEFAULT_CURVE,
    val greenCurve: List<Float> = DEFAULT_CURVE,
    val blueCurve: List<Float> = DEFAULT_CURVE,

    // 2. Photoshop 3-Way Color Balance (Shadows / Midtones / Highlights, -1..+1 each)
    val preserveLuminosity: Boolean = true,
    val shadowCyanRed: Float = 0f,
    val shadowMagentaGreen: Float = 0f,
    val shadowYellowBlue: Float = 0f,
    val midCyanRed: Float = 0f,
    val midMagentaGreen: Float = 0f,
    val midYellowBlue: Float = 0f,
    val highCyanRed: Float = 0f,
    val highMagentaGreen: Float = 0f,
    val highYellowBlue: Float = 0f,

    // 3. Photoshop Blend-Mode Color Layer + Blend-If Tonal Mask
    val blendMode: PsBlendMode = PsBlendMode.NONE,
    val blendRange: PsBlendRange = PsBlendRange.ALL_PROTECTED,
    val blendHueDeg: Float = 198f,
    val blendChroma: Float = 0.68f,
    val blendOpacity: Float = 0f,

    // 4. Targeted 3-Region HSL (Portrait Skin Orange, Foliage Green, Sky Cyan/Blue)
    val skinLuminance: Float = 0f,
    val skinSaturation: Float = 0f,
    val greenHue: Float = 0f,
    val greenSaturation: Float = 0f,
    val blueHue: Float = 0f,
    val blueLuminance: Float = 0f,

    // 5. Finishing Polish: Clarity (Midtone Micro-Contrast), Output Sharpening, Optical Lens Vignette
    val clarity: Float = 0f,
    val sharpen: Float = 0f,
    val vignette: Float = 0f
) {
    val hasWhiteBalance: Boolean
        get() = abs(temperature) >= 0.01f || abs(tint) >= 0.01f

    val hasToneAdjust: Boolean
        get() = abs(highlights) >= 0.01f || abs(shadows) >= 0.01f || abs(contrast) >= 0.01f

    val hasToneCurve: Boolean
        get() = !isLinearCurve(lumaCurve) || !isLinearCurve(redCurve) || !isLinearCurve(greenCurve) || !isLinearCurve(blueCurve)

    val hasShadowBalance: Boolean
        get() = abs(shadowCyanRed) >= 0.01f || abs(shadowMagentaGreen) >= 0.01f || abs(shadowYellowBlue) >= 0.01f

    val hasMidBalance: Boolean
        get() = abs(midCyanRed) >= 0.01f || abs(midMagentaGreen) >= 0.01f || abs(midYellowBlue) >= 0.01f

    val hasHighBalance: Boolean
        get() = abs(highCyanRed) >= 0.01f || abs(highMagentaGreen) >= 0.01f || abs(highYellowBlue) >= 0.01f

    val hasColorBalance: Boolean
        get() = hasShadowBalance || hasMidBalance || hasHighBalance

    val hasBlendLayer: Boolean
        get() = blendMode != PsBlendMode.NONE && blendOpacity >= 0.01f

    val hasHslAdjust: Boolean
        get() = abs(skinLuminance) >= 0.01f || abs(skinSaturation) >= 0.01f ||
            abs(greenHue) >= 0.01f || abs(greenSaturation) >= 0.01f ||
            abs(blueHue) >= 0.01f || abs(blueLuminance) >= 0.01f

    val hasFinishingAdjust: Boolean
        get() = abs(clarity) >= 0.01f || sharpen >= 0.01f || abs(vignette) >= 0.01f || abs(dehaze) >= 0.01f

    val isIdentity: Boolean
        get() = !hasWhiteBalance &&
            !hasToneAdjust &&
            !hasToneCurve &&
            abs(vibrance) < 0.01f &&
            abs(saturation) < 0.01f &&
            !hasColorBalance &&
            !hasBlendLayer &&
            !hasHslAdjust &&
            !hasFinishingAdjust

    fun curveFor(channel: CurveChannel): List<Float> = when (channel) {
        CurveChannel.LUMA -> lumaCurve
        CurveChannel.RED -> redCurve
        CurveChannel.GREEN -> greenCurve
        CurveChannel.BLUE -> blueCurve
    }

    fun withCurve(channel: CurveChannel, points: List<Float>): PostToneConfig = when (channel) {
        CurveChannel.LUMA -> copy(lumaCurve = points)
        CurveChannel.RED -> copy(redCurve = points)
        CurveChannel.GREEN -> copy(greenCurve = points)
        CurveChannel.BLUE -> copy(blueCurve = points)
    }

    fun hasChannelCurve(channel: CurveChannel): Boolean = !isLinearCurve(curveFor(channel))

    fun hasZoneChanges(zone: ColorBalanceZone): Boolean = when (zone) {
        ColorBalanceZone.SHADOWS -> hasShadowBalance
        ColorBalanceZone.MIDTONES -> hasMidBalance
        ColorBalanceZone.HIGHLIGHTS -> hasHighBalance
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("temperature", temperature.toDouble())
        put("tint", tint.toDouble())
        put("highlights", highlights.toDouble())
        put("dehaze", dehaze.toDouble())
        if (!isLinearCurve(lumaCurve)) put("lumaCurve", JSONArray(lumaCurve.map { it.toDouble() }))
        if (!isLinearCurve(redCurve)) put("redCurve", JSONArray(redCurve.map { it.toDouble() }))
        if (!isLinearCurve(greenCurve)) put("greenCurve", JSONArray(greenCurve.map { it.toDouble() }))
        if (!isLinearCurve(blueCurve)) put("blueCurve", JSONArray(blueCurve.map { it.toDouble() }))
        put("shadows", shadows.toDouble())
        put("contrast", contrast.toDouble())
        put("vibrance", vibrance.toDouble())
        put("saturation", saturation.toDouble())
        put("preserveLuminosity", preserveLuminosity)
        put("shadowCyanRed", shadowCyanRed.toDouble())
        put("shadowMagentaGreen", shadowMagentaGreen.toDouble())
        put("shadowYellowBlue", shadowYellowBlue.toDouble())
        put("midCyanRed", midCyanRed.toDouble())
        put("midMagentaGreen", midMagentaGreen.toDouble())
        put("midYellowBlue", midYellowBlue.toDouble())
        put("highCyanRed", highCyanRed.toDouble())
        put("highMagentaGreen", highMagentaGreen.toDouble())
        put("highYellowBlue", highYellowBlue.toDouble())
        put("blendMode", blendMode.name)
        put("blendRange", blendRange.name)
        put("blendHueDeg", blendHueDeg.toDouble())
        put("blendChroma", blendChroma.toDouble())
        put("blendOpacity", blendOpacity.toDouble())
        put("skinLuminance", skinLuminance.toDouble())
        put("skinSaturation", skinSaturation.toDouble())
        put("greenHue", greenHue.toDouble())
        put("greenSaturation", greenSaturation.toDouble())
        put("blueHue", blueHue.toDouble())
        put("blueLuminance", blueLuminance.toDouble())
        put("clarity", clarity.toDouble())
        put("sharpen", sharpen.toDouble())
        put("vignette", vignette.toDouble())
    }

    companion object {
        val DEFAULT_CURVE: List<Float> = listOf(0.0f, 0.25f, 0.50f, 0.75f, 1.0f)

        fun isLinearCurve(pts: List<Float>): Boolean {
            if (pts.size != 5) return true
            return abs(pts[0] - 0.0f) < 0.008f &&
                abs(pts[1] - 0.25f) < 0.008f &&
                abs(pts[2] - 0.50f) < 0.008f &&
                abs(pts[3] - 0.75f) < 0.008f &&
                abs(pts[4] - 1.0f) < 0.008f
        }

        private fun parseCurve(arr: JSONArray?): List<Float> {
            if (arr == null || arr.length() != 5) return DEFAULT_CURVE
            return List(5) { i -> arr.optDouble(i, DEFAULT_CURVE[i].toDouble()).toFloat().coerceIn(0f, 1f) }
        }

        fun fromJson(obj: JSONObject?): PostToneConfig {
            if (obj == null) return PostToneConfig()
            val bm = PsBlendMode.entries.firstOrNull { it.name == obj.optString("blendMode") } ?: PsBlendMode.NONE
            val br = PsBlendRange.entries.firstOrNull { it.name == obj.optString("blendRange") } ?: PsBlendRange.ALL_PROTECTED
            return PostToneConfig(
                temperature = obj.optDouble("temperature", 0.0).toFloat().coerceIn(-1f, 1f),
                tint = obj.optDouble("tint", 0.0).toFloat().coerceIn(-1f, 1f),
                highlights = obj.optDouble("highlights", 0.0).toFloat().coerceIn(-1f, 1f),
                dehaze = obj.optDouble("dehaze", 0.0).toFloat().coerceIn(-1f, 1f),
                lumaCurve = parseCurve(obj.optJSONArray("lumaCurve")),
                redCurve = parseCurve(obj.optJSONArray("redCurve")),
                greenCurve = parseCurve(obj.optJSONArray("greenCurve")),
                blueCurve = parseCurve(obj.optJSONArray("blueCurve")),
                shadows = obj.optDouble("shadows", 0.0).toFloat().coerceIn(-1f, 1f),
                contrast = obj.optDouble("contrast", 0.0).toFloat().coerceIn(-1f, 1f),
                vibrance = obj.optDouble("vibrance", 0.0).toFloat().coerceIn(-1f, 1f),
                saturation = obj.optDouble("saturation", 0.0).toFloat().coerceIn(-1f, 1f),
                preserveLuminosity = obj.optBoolean("preserveLuminosity", true),
                shadowCyanRed = obj.optDouble("shadowCyanRed", 0.0).toFloat().coerceIn(-1f, 1f),
                shadowMagentaGreen = obj.optDouble("shadowMagentaGreen", 0.0).toFloat().coerceIn(-1f, 1f),
                shadowYellowBlue = obj.optDouble("shadowYellowBlue", 0.0).toFloat().coerceIn(-1f, 1f),
                midCyanRed = obj.optDouble("midCyanRed", 0.0).toFloat().coerceIn(-1f, 1f),
                midMagentaGreen = obj.optDouble("midMagentaGreen", 0.0).toFloat().coerceIn(-1f, 1f),
                midYellowBlue = obj.optDouble("midYellowBlue", 0.0).toFloat().coerceIn(-1f, 1f),
                highCyanRed = obj.optDouble("highCyanRed", 0.0).toFloat().coerceIn(-1f, 1f),
                highMagentaGreen = obj.optDouble("highMagentaGreen", 0.0).toFloat().coerceIn(-1f, 1f),
                highYellowBlue = obj.optDouble("highYellowBlue", 0.0).toFloat().coerceIn(-1f, 1f),
                blendMode = bm,
                blendRange = br,
                blendHueDeg = obj.optDouble("blendHueDeg", 198.0).toFloat().coerceIn(0f, 360f),
                blendChroma = obj.optDouble("blendChroma", 0.68).toFloat().coerceIn(0.15f, 1f),
                blendOpacity = obj.optDouble("blendOpacity", 0.0).toFloat().coerceIn(0f, 0.85f),
                skinLuminance = obj.optDouble("skinLuminance", 0.0).toFloat().coerceIn(-1f, 1f),
                skinSaturation = obj.optDouble("skinSaturation", 0.0).toFloat().coerceIn(-1f, 1f),
                greenHue = obj.optDouble("greenHue", 0.0).toFloat().coerceIn(-1f, 1f),
                greenSaturation = obj.optDouble("greenSaturation", 0.0).toFloat().coerceIn(-1f, 1f),
                blueHue = obj.optDouble("blueHue", 0.0).toFloat().coerceIn(-1f, 1f),
                blueLuminance = obj.optDouble("blueLuminance", 0.0).toFloat().coerceIn(-1f, 1f),
                clarity = obj.optDouble("clarity", 0.0).toFloat().coerceIn(-1f, 1f),
                sharpen = obj.optDouble("sharpen", 0.0).toFloat().coerceIn(0f, 1f),
                vignette = obj.optDouble("vignette", 0.0).toFloat().coerceIn(-1f, 1f)
            )
        }
    }
}

enum class PsGradePreset(
    val label: String,
    val desc: String,
    val primaryHex: Long,
    val secondaryHex: Long,
    val config: PostToneConfig
) {
    NONE(
        "原色归零", "清空全部混色", 0xFF6B7280, 0xFF9CA3AF,
        PostToneConfig()
    ),
    TEAL_ORANGE(
        "青橙电影", "暗部青蓝 · 高光暖橙", 0xFF1B8BA8, 0xFFE58235,
        PostToneConfig(
            contrast = 0.14f,
            vibrance = 0.18f,
            shadowCyanRed = -0.32f,
            shadowYellowBlue = 0.24f,
            highCyanRed = 0.24f,
            highYellowBlue = -0.26f,
            blendMode = PsBlendMode.SOFT_LIGHT,
            blendRange = PsBlendRange.HIGHLIGHTS,
            blendHueDeg = 32f,
            blendChroma = 0.65f,
            blendOpacity = 0.16f,
            blueHue = -0.22f
        )
    ),
    JAPANESE_AIRY(
        "日系清透", "青蓝空气感 · 干净肤色", 0xFF67B8D6, 0xFFF2D6B8,
        PostToneConfig(
            highlights = -0.14f,
            shadows = 0.24f,
            contrast = -0.10f,
            vibrance = 0.10f,
            shadowCyanRed = -0.20f,
            shadowYellowBlue = 0.22f,
            highYellowBlue = -0.10f,
            blendMode = PsBlendMode.SCREEN,
            blendRange = PsBlendRange.SHADOWS,
            blendHueDeg = 204f,
            blendChroma = 0.48f,
            blendOpacity = 0.10f,
            skinLuminance = 0.22f,
            skinSaturation = -0.12f,
            blueHue = -0.35f,
            blueLuminance = 0.16f
        )
    ),
    KODAK_GOLD(
        "柯达暖金", "胶片暖阳 · 暗部微青", 0xFFDFA33C, 0xFF426E78,
        PostToneConfig(
            contrast = 0.12f,
            vibrance = 0.16f,
            shadowCyanRed = -0.14f,
            shadowYellowBlue = 0.12f,
            midCyanRed = 0.16f,
            midYellowBlue = -0.22f,
            highCyanRed = 0.18f,
            highYellowBlue = -0.28f,
            blendMode = PsBlendMode.SOFT_LIGHT,
            blendRange = PsBlendRange.MIDTONES,
            blendHueDeg = 42f,
            blendChroma = 0.62f,
            blendOpacity = 0.18f,
            greenHue = -0.25f
        )
    ),
    FUJI_CLASSIC(
        "富士青绿", "墨绿暗调 · 清冷建筑", 0xFF2D8C73, 0xFFB8768C,
        PostToneConfig(
            highlights = -0.18f,
            contrast = 0.10f,
            vibrance = 0.12f,
            shadowCyanRed = -0.26f,
            shadowMagentaGreen = 0.22f,
            midMagentaGreen = -0.08f,
            highYellowBlue = -0.08f,
            blendMode = PsBlendMode.COLOR,
            blendRange = PsBlendRange.SHADOWS,
            blendHueDeg = 166f,
            blendChroma = 0.55f,
            blendOpacity = 0.15f,
            greenHue = 0.35f,
            greenSaturation = -0.12f,
            blueLuminance = -0.18f
        )
    ),
    WONG_KAR_WAI(
        "王家卫暗调", "浓绿暗部 · 琥珀霓虹", 0xFF1F7A5A, 0xFFD99429,
        PostToneConfig(
            shadows = -0.12f,
            contrast = 0.24f,
            shadowCyanRed = -0.34f,
            shadowMagentaGreen = 0.36f,
            midYellowBlue = -0.16f,
            highCyanRed = 0.22f,
            highYellowBlue = -0.32f,
            blendMode = PsBlendMode.SOFT_LIGHT,
            blendRange = PsBlendRange.ALL_PROTECTED,
            blendHueDeg = 152f,
            blendChroma = 0.72f,
            blendOpacity = 0.22f
        )
    ),
    PORTRAIT_GLOW(
        "通透人像", "柔粉高光 · 清透肤质", 0xFFE8A4B4, 0xFF7FA6C4,
        PostToneConfig(
            highlights = -0.10f,
            shadows = 0.18f,
            vibrance = 0.12f,
            shadowCyanRed = -0.10f,
            shadowYellowBlue = 0.14f,
            highCyanRed = 0.14f,
            highMagentaGreen = -0.10f,
            blendMode = PsBlendMode.SOFT_LIGHT,
            blendRange = PsBlendRange.HIGHLIGHTS,
            blendHueDeg = 350f,
            blendChroma = 0.45f,
            blendOpacity = 0.12f,
            skinLuminance = 0.26f,
            skinSaturation = -0.10f
        )
    )
}

/**
 * Photoshop-Grade Post-Darkroom Color Grading Engine:
 * 1. Tonal Curve (Highlights / Shadows / Contrast) with luminance-preserving soft knee.
 * 2. PS 3-Way Color Balance (Shadows / Midtones / Highlights C-R / M-G / Y-B) with exact W3C/PS
 *    Preserve Luminosity (`setLum`) and black/white endpoint anchoring (zero flat color veil!).
 * 3. PS Blend-Mode Color Layer (Soft Light / Color / Overlay / Screen / Multiply) + Blend-If Tonal Range.
 * 4. PS Vibrance (skin-protected low-saturation boost) + Global Saturation.
 * 5. Targeted 3-Region HSL (Portrait Skin Orange, Foliage Green, Sky Cyan/Blue).
 * 6. Vintage Quartz Glowing Date Imprint ('26 11 24).
 */
object PostDarkroomAdjuster {

    /**
     * Builds a 256-entry smooth monotonic Fritsch-Carlson cubic Hermite LUT from 5 control points
     * at fixed x = [0.0, 0.25, 0.50, 0.75, 1.0]. Zero overshoot / ringing guaranteed!
     */
    fun buildCurveLut256(points: List<Float>): FloatArray {
        val y = FloatArray(5) { i -> points.getOrElse(i) { PostToneConfig.DEFAULT_CURVE[i] }.coerceIn(0f, 1f) }
        val h = 0.25f
        val d = FloatArray(4) { i -> (y[i + 1] - y[i]) / h }
        val m = FloatArray(5)
        m[0] = d[0]
        m[4] = d[3]
        for (i in 1..3) {
            m[i] = if (d[i - 1] * d[i] <= 0f) 0f else 0.5f * (d[i - 1] + d[i])
        }
        // Fritsch-Carlson monotonicity limiter
        for (i in 0..3) {
            if (abs(d[i]) < 1e-5f) {
                m[i] = 0f
                m[i + 1] = 0f
            } else {
                val a = m[i] / d[i]
                val b = m[i + 1] / d[i]
                val s = a * a + b * b
                if (s > 9f) {
                    val tau = 3f / sqrt(s)
                    m[i] = tau * a * d[i]
                    m[i + 1] = tau * b * d[i]
                }
            }
        }
        return FloatArray(256) { idx ->
            val x = idx / 255f
            val seg = (x * 4f).toInt().coerceIn(0, 3)
            val t = ((x - seg * h) / h).coerceIn(0f, 1f)
            val t2 = t * t
            val t3 = t2 * t
            val h00 = 2f * t3 - 3f * t2 + 1f
            val h10 = t3 - 2f * t2 + t
            val h01 = -2f * t3 + 3f * t2
            val h11 = t3 - t2
            (h00 * y[seg] + h10 * h * m[seg] + h01 * y[seg + 1] + h11 * h * m[seg + 1]).coerceIn(0f, 1f)
        }
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun hueToRgb(hueDeg: Float, chroma: Float, value: Float = 0.78f): FloatArray {
        val h = ((hueDeg % 360f) + 360f) % 360f
        val c = value * chroma.coerceIn(0f, 1f)
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = value - c
        val (r1, g1, b1) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return floatArrayOf((r1 + m).coerceIn(0f, 1f), (g1 + m).coerceIn(0f, 1f), (b1 + m).coerceIn(0f, 1f))
    }

    /**
     * Exact W3C / Photoshop `SetLum` + `ClipColor`:
     * Shifts RGB to match `targetLum` (Rec.709 / Rec.601 luminance) while preserving exact hue
     * and smoothly desaturating ONLY when a channel would exceed [0, 1].
     */
    private inline fun applySetLum(
        rIn: Float, gIn: Float, bIn: Float,
        targetLum: Float,
        out: FloatArray
    ) {
        val curLum = 0.299f * rIn + 0.587f * gIn + 0.114f * bIn
        val d = targetLum - curLum
        var r = rIn + d
        var g = gIn + d
        var b = bIn + d
        val n = min(r, min(g, b))
        val x = max(r, max(g, b))
        if (n < 0f) {
            val denom = (targetLum - n).coerceAtLeast(1e-5f)
            r = targetLum + ((r - targetLum) * targetLum) / denom
            g = targetLum + ((g - targetLum) * targetLum) / denom
            b = targetLum + ((b - targetLum) * targetLum) / denom
        }
        if (x > 1f) {
            val denom = (x - targetLum).coerceAtLeast(1e-5f)
            val oneMinusL = 1f - targetLum
            r = targetLum + ((r - targetLum) * oneMinusL) / denom
            g = targetLum + ((g - targetLum) * oneMinusL) / denom
            b = targetLum + ((b - targetLum) * oneMinusL) / denom
        }
        out[0] = r.coerceIn(0f, 1f)
        out[1] = g.coerceIn(0f, 1f)
        out[2] = b.coerceIn(0f, 1f)
    }

    private fun softLightChannel(b: Float, s: Float): Float {
        return if (s <= 0.5f) {
            b - (1f - 2f * s) * b * (1f - b)
        } else {
            val d = if (b <= 0.25f) ((16f * b - 12f) * b + 4f) * b else sqrt(b)
            b + (2f * s - 1f) * (d - b)
        }.coerceIn(0f, 1f)
    }

    private fun overlayChannel(b: Float, s: Float): Float {
        return (if (b < 0.5f) 2f * b * s else 1f - 2f * (1f - b) * (1f - s)).coerceIn(0f, 1f)
    }

    private fun hsvToRgbInline(hDeg: Float, s: Float, v: Float, out: FloatArray) {
        val h = ((hDeg % 360f) + 360f) % 360f
        val c = v * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = v - c
        val r1: Float
        val g1: Float
        val b1: Float
        when {
            h < 60f -> { r1 = c; g1 = x; b1 = 0f }
            h < 120f -> { r1 = x; g1 = c; b1 = 0f }
            h < 180f -> { r1 = 0f; g1 = c; b1 = x }
            h < 240f -> { r1 = 0f; g1 = x; b1 = c }
            h < 300f -> { r1 = x; g1 = 0f; b1 = c }
            else -> { r1 = c; g1 = 0f; b1 = x }
        }
        out[0] = (r1 + m).coerceIn(0f, 1f)
        out[1] = (g1 + m).coerceIn(0f, 1f)
        out[2] = (b1 + m).coerceIn(0f, 1f)
    }

    /**
     * Memory-safe 256-row horizontal strip streaming processor for all 8 PS-grade stages.
     * Peak Java heap on a 36.4MP (7360x4912) Pentax K-1 photo is <= 15 MB (vs 289 MB full-array),
     * completely eliminating OutOfMemoryError crashes during full-resolution export!
     */
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
            val outBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

            val hi = tone.highlights.coerceIn(-1f, 1f)
            val sh = tone.shadows.coerceIn(-1f, 1f)
            val ct = tone.contrast.coerceIn(-1f, 1f)
            val vib = tone.vibrance.coerceIn(-1f, 1f)
            val satMult = (1f + tone.saturation.coerceIn(-1f, 1f) * 0.75f).coerceAtLeast(0f)

            val tempVal = tone.temperature.coerceIn(-1f, 1f)
            val tintVal = tone.tint.coerceIn(-1f, 1f)
            val doWb = tone.hasWhiteBalance
            val wbR = (1f + tempVal * 0.20f + tintVal * 0.12f).coerceIn(0.60f, 1.45f)
            val wbG = (1f - tintVal * 0.16f).coerceIn(0.65f, 1.38f)
            val wbB = (1f - tempVal * 0.22f + tintVal * 0.10f).coerceIn(0.60f, 1.45f)

            val doTone = tone.hasToneAdjust
            val doCurve = tone.hasToneCurve
            val lumaCurveLut = if (!PostToneConfig.isLinearCurve(tone.lumaCurve)) buildCurveLut256(tone.lumaCurve) else null
            val redCurveLut = if (!PostToneConfig.isLinearCurve(tone.redCurve)) buildCurveLut256(tone.redCurve) else null
            val greenCurveLut = if (!PostToneConfig.isLinearCurve(tone.greenCurve)) buildCurveLut256(tone.greenCurve) else null
            val blueCurveLut = if (!PostToneConfig.isLinearCurve(tone.blueCurve)) buildCurveLut256(tone.blueCurve) else null

            val doCB = tone.hasColorBalance
            val doBlend = tone.hasBlendLayer
            val doVib = abs(vib) >= 0.01f
            val doSat = abs(tone.saturation) >= 0.01f
            val doHsl = tone.hasHslAdjust
            val preserveLum = tone.preserveLuminosity

            val dehazeVal = tone.dehaze.coerceIn(-1f, 1f)
            val clarityVal = tone.clarity.coerceIn(-1f, 1f)
            val sharpenVal = tone.sharpen.coerceIn(0f, 1f)
            val vignetteVal = tone.vignette.coerceIn(-1f, 1f)
            val doDehaze = abs(dehazeVal) >= 0.01f && w >= 16 && h >= 16
            val doClarity = abs(clarityVal) >= 0.01f && w >= 16 && h >= 16
            val doSharpen = sharpenVal >= 0.01f && w >= 4 && h >= 4
            val doVignette = abs(vignetteVal) >= 0.01f

            val toneLut = FloatArray(256)
            val shadowMaskLut = FloatArray(256)
            val midMaskLut = FloatArray(256)
            val highMaskLut = FloatArray(256)
            val blendRangeLut = FloatArray(256)

            for (i in 0..255) {
                val lv = i / 255f
                var v = lv
                if (abs(sh) >= 0.01f) {
                    val mask = (1f - v).pow(2.4f)
                    v += sh * 0.34f * mask * (0.25f + 0.75f * v * 2f).coerceAtMost(1f)
                }
                if (abs(hi) >= 0.01f) {
                    val mask = v.pow(2.4f)
                    v += hi * 0.30f * mask * (1.15f - 0.15f * v)
                }
                v = v.coerceIn(0f, 1f)
                if (abs(ct) >= 0.01f) {
                    val sCurve = v * v * (3f - 2f * v)
                    v = if (ct > 0f) v + (sCurve - v) * (ct * 0.85f) else v + (0.5f - v) * (-ct * 0.42f)
                }
                toneLut[i] = v.coerceIn(0f, 1f)

                val blackAnchor = smoothstep(0.0f, 0.085f, lv)
                val whiteAnchor = 1f - smoothstep(0.90f, 1.0f, lv)
                shadowMaskLut[i] = (1f - smoothstep(0.08f, 0.56f, lv)) * blackAnchor
                midMaskLut[i] = sin(lv * PI).toFloat().coerceAtLeast(0f).pow(1.4f)
                highMaskLut[i] = smoothstep(0.42f, 0.90f, lv) * whiteAnchor
                blendRangeLut[i] = when (tone.blendRange) {
                    PsBlendRange.ALL_PROTECTED -> blackAnchor * whiteAnchor
                    PsBlendRange.SHADOWS -> shadowMaskLut[i]
                    PsBlendRange.MIDTONES -> midMaskLut[i]
                    PsBlendRange.HIGHLIGHTS -> highMaskLut[i]
                }
            }

            val cbGain = 0.28f
            val sR = cbGain * (tone.shadowCyanRed - 0.5f * tone.shadowMagentaGreen - 0.5f * tone.shadowYellowBlue)
            val sG = cbGain * (tone.shadowMagentaGreen - 0.5f * tone.shadowCyanRed - 0.5f * tone.shadowYellowBlue)
            val sB = cbGain * (tone.shadowYellowBlue - 0.5f * tone.shadowCyanRed - 0.5f * tone.shadowMagentaGreen)
            val mR = cbGain * (tone.midCyanRed - 0.5f * tone.midMagentaGreen - 0.5f * tone.midYellowBlue)
            val mG = cbGain * (tone.midMagentaGreen - 0.5f * tone.midCyanRed - 0.5f * tone.midYellowBlue)
            val mB = cbGain * (tone.midYellowBlue - 0.5f * tone.midCyanRed - 0.5f * tone.midMagentaGreen)
            val hR = cbGain * (tone.highCyanRed - 0.5f * tone.highMagentaGreen - 0.5f * tone.highYellowBlue)
            val hG = cbGain * (tone.highMagentaGreen - 0.5f * tone.highCyanRed - 0.5f * tone.highYellowBlue)
            val hB = cbGain * (tone.highYellowBlue - 0.5f * tone.highCyanRed - 0.5f * tone.highMagentaGreen)

            val blendVal = when (tone.blendMode) {
                PsBlendMode.SCREEN -> 0.88f
                PsBlendMode.MULTIPLY -> 0.82f
                PsBlendMode.COLOR -> 0.72f
                else -> 0.76f
            }
            val blendRgb = hueToRgb(tone.blendHueDeg, tone.blendChroma, blendVal)
            val bR = blendRgb[0]; val bG = blendRgb[1]; val bB = blendRgb[2]
            val blendOp = tone.blendOpacity.coerceIn(0f, 0.85f)
            val blendMode = tone.blendMode

            val skinLum = tone.skinLuminance.coerceIn(-1f, 1f)
            val skinSat = tone.skinSaturation.coerceIn(-1f, 1f)
            val greenHueShift = tone.greenHue.coerceIn(-1f, 1f)
            val greenSat = tone.greenSaturation.coerceIn(-1f, 1f)
            val blueHueShift = tone.blueHue.coerceIn(-1f, 1f)
            val blueLum = tone.blueLuminance.coerceIn(-1f, 1f)
            val doSkin = abs(skinLum) >= 0.01f || abs(skinSat) >= 0.01f
            val doGreen = abs(greenHueShift) >= 0.01f || abs(greenSat) >= 0.01f
            val doBlue = abs(blueHueShift) >= 0.01f || abs(blueLum) >= 0.01f

            val inv255 = 1f / 255f

            fun transformPixelStages0to5(px: Int, tmpRgb: FloatArray): Int {
                val a = px and 0xFF000000.toInt()
                var r = ((px ushr 16) and 0xFF) * inv255
                var g = ((px ushr 8) and 0xFF) * inv255
                var b = (px and 0xFF) * inv255

                if (doWb) {
                    val lumaWb = (0.299f * r + 0.587f * g + 0.114f * b).coerceIn(0f, 1f)
                    applySetLum(
                        (r * wbR).coerceIn(0f, 1f),
                        (g * wbG).coerceIn(0f, 1f),
                        (b * wbB).coerceIn(0f, 1f),
                        lumaWb,
                        tmpRgb
                    )
                    r = tmpRgb[0]; g = tmpRgb[1]; b = tmpRgb[2]
                }

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

                if (doCurve) {
                    if (lumaCurveLut != null) {
                        r = lumaCurveLut[(r * 255f + 0.5f).toInt().coerceIn(0, 255)]
                        g = lumaCurveLut[(g * 255f + 0.5f).toInt().coerceIn(0, 255)]
                        b = lumaCurveLut[(b * 255f + 0.5f).toInt().coerceIn(0, 255)]
                    }
                    if (redCurveLut != null) r = redCurveLut[(r * 255f + 0.5f).toInt().coerceIn(0, 255)]
                    if (greenCurveLut != null) g = greenCurveLut[(g * 255f + 0.5f).toInt().coerceIn(0, 255)]
                    if (blueCurveLut != null) b = blueCurveLut[(b * 255f + 0.5f).toInt().coerceIn(0, 255)]
                }

                if (doCB) {
                    val lumaOrig = (0.299f * r + 0.587f * g + 0.114f * b).coerceIn(0f, 1f)
                    val lIdx = (lumaOrig * 255f + 0.5f).toInt().coerceIn(0, 255)
                    val ws = shadowMaskLut[lIdx]; val wm = midMaskLut[lIdx]; val wh = highMaskLut[lIdx]
                    r = (r + ws * sR + wm * mR + wh * hR).coerceIn(0f, 1f)
                    g = (g + ws * sG + wm * mG + wh * hG).coerceIn(0f, 1f)
                    b = (b + ws * sB + wm * mB + wh * hB).coerceIn(0f, 1f)
                    if (preserveLum) {
                        applySetLum(r, g, b, lumaOrig, tmpRgb)
                        r = tmpRgb[0]; g = tmpRgb[1]; b = tmpRgb[2]
                    }
                }

                if (doBlend) {
                    val lumaNow = (0.299f * r + 0.587f * g + 0.114f * b).coerceIn(0f, 1f)
                    val lIdx = (lumaNow * 255f + 0.5f).toInt().coerceIn(0, 255)
                    val effAlpha = blendOp * blendRangeLut[lIdx]
                    if (effAlpha > 0.002f) {
                        val mr: Float; val mg: Float; val mb: Float
                        when (blendMode) {
                            PsBlendMode.SOFT_LIGHT -> {
                                mr = softLightChannel(r, bR); mg = softLightChannel(g, bG); mb = softLightChannel(b, bB)
                            }
                            PsBlendMode.OVERLAY -> {
                                mr = overlayChannel(r, bR); mg = overlayChannel(g, bG); mb = overlayChannel(b, bB)
                            }
                            PsBlendMode.SCREEN -> {
                                mr = 1f - (1f - r) * (1f - bR); mg = 1f - (1f - g) * (1f - bG); mb = 1f - (1f - b) * (1f - bB)
                            }
                            PsBlendMode.MULTIPLY -> {
                                mr = r * bR; mg = g * bG; mb = b * bB
                            }
                            PsBlendMode.COLOR -> {
                                applySetLum(bR, bG, bB, lumaNow, tmpRgb)
                                mr = tmpRgb[0]; mg = tmpRgb[1]; mb = tmpRgb[2]
                            }
                            PsBlendMode.NONE -> {
                                mr = r; mg = g; mb = b
                            }
                        }
                        val invA = 1f - effAlpha
                        r = (r * invA + mr * effAlpha).coerceIn(0f, 1f)
                        g = (g * invA + mg * effAlpha).coerceIn(0f, 1f)
                        b = (b * invA + mb * effAlpha).coerceIn(0f, 1f)
                    }
                }

                if (doVib || doSat) {
                    val luma = 0.299f * r + 0.587f * g + 0.114f * b
                    var mult = if (doSat) satMult else 1f
                    if (doVib) {
                        val maxC = max(r, max(g, b))
                        val minC = min(r, min(g, b))
                        val curSat = if (maxC > 0.005f) (maxC - minC) / maxC else 0f
                        val skinProt = if (r > g && g > b && (r - b) > 0.04f) {
                            val ratio = ((g - b) / (r - b)).coerceIn(0f, 1f)
                            1f - abs(ratio - 0.42f) * 2.1f
                        } else 0f
                        val prot = skinProt.coerceIn(0f, 1f)
                        val vibScale = if (vib > 0f) (1f - curSat) * (1f - 0.65f * prot) else 1f
                        mult *= (1f + vib * 0.85f * vibScale).coerceAtLeast(0f)
                    }
                    r = (luma + (r - luma) * mult).coerceIn(0f, 1f)
                    g = (luma + (g - luma) * mult).coerceIn(0f, 1f)
                    b = (luma + (b - luma) * mult).coerceIn(0f, 1f)
                }

                if (doHsl) {
                    val maxC = max(r, max(g, b))
                    val minC = min(r, min(g, b))
                    val delta = maxC - minC
                    if (delta > 0.025f && maxC > 0.08f) {
                        var hueDeg = when (maxC) {
                            r -> 60f * (((g - b) / delta) % 6f)
                            g -> 60f * (((b - r) / delta) + 2f)
                            else -> 60f * (((r - g) / delta) + 4f)
                        }.let { if (it < 0f) it + 360f else it }
                        var satC = delta / maxC
                        var valC = maxC
                        var modified = false

                        if (doSkin && maxC > 0.12f) {
                            var dHue = abs(hueDeg - 24f)
                            if (dHue > 180f) dHue = 360f - dHue
                            if (dHue < 32f) {
                                val satGate = ((satC - 0.08f) / 0.18f).coerceIn(0f, 1f) *
                                    (1f - ((satC - 0.78f) / 0.22f).coerceIn(0f, 1f))
                                val wSkin = (1f - dHue / 32f).pow(1.6f) * satGate
                                if (wSkin > 0.005f) {
                                    valC = (valC * (1f + skinLum * 0.24f * wSkin)).coerceIn(0f, 1f)
                                    satC = (satC * (1f + skinSat * 0.55f * wSkin)).coerceIn(0f, 1f)
                                    modified = true
                                }
                            }
                        }

                        if (doGreen) {
                            val dGreen = abs(hueDeg - 115f)
                            if (dGreen < 45f) {
                                val wGreen = (1f - dGreen / 45f).pow(1.5f) * ((satC - 0.08f) / 0.20f).coerceIn(0f, 1f)
                                if (wGreen > 0.005f) {
                                    hueDeg += greenHueShift * 36f * wGreen
                                    satC = (satC * (1f + greenSat * 0.60f * wGreen)).coerceIn(0f, 1f)
                                    modified = true
                                }
                            }
                        }

                        if (doBlue) {
                            val dBlue = abs(hueDeg - 215f)
                            if (dBlue < 45f) {
                                val wBlue = (1f - dBlue / 45f).pow(1.5f) * ((satC - 0.08f) / 0.20f).coerceIn(0f, 1f)
                                if (wBlue > 0.005f) {
                                    hueDeg += blueHueShift * 32f * wBlue
                                    valC = (valC * (1f + blueLum * 0.34f * wBlue)).coerceIn(0f, 1f)
                                    modified = true
                                }
                            }
                        }

                        if (modified) {
                            hsvToRgbInline(hueDeg, satC, valC, tmpRgb)
                            r = tmpRgb[0]; g = tmpRgb[1]; b = tmpRgb[2]
                        }
                    }
                }

                val ri = (r * 255f + 0.5f).toInt().coerceIn(0, 255)
                val gi = (g * 255f + 0.5f).toInt().coerceIn(0, 255)
                val bi = (b * 255f + 0.5f).toInt().coerceIn(0, 255)
                return a or (ri shl 16) or (gi shl 8) or bi
            }

            // Precompute tiny (< 80 KB) global guide fields for Dehaze (96px) & Clarity (144px) — ZERO full-frame arrays!
            var smoothDark: FloatArray? = null
            var dw = 0; var dh = 0
            var atmR = 0.88f; var atmG = 0.90f; var atmB = 0.94f
            if (doDehaze) {
                val maxLow = 96
                val sc = maxLow.toFloat() / max(w, h).toFloat()
                dw = (w * sc).toInt().coerceIn(16, min(w, maxLow))
                dh = (h * sc).toInt().coerceIn(16, min(h, maxLow))
                val darkSmall = FloatArray(dw * dh)
                val tmp = FloatArray(3)
                var maxBright = -1f
                for (sy in 0 until dh) {
                    val y0 = ((sy.toLong() * h) / dh).toInt().coerceIn(0, h - 1)
                    val dOff = sy * dw
                    for (sx in 0 until dw) {
                        val x0 = ((sx.toLong() * w) / dw).toInt().coerceIn(0, w - 1)
                        val px = transformPixelStages0to5(source.getPixel(x0, y0), tmp)
                        val pr = ((px ushr 16) and 0xFF) * inv255
                        val pg = ((px ushr 8) and 0xFF) * inv255
                        val pb = (px and 0xFF) * inv255
                        val dc = min(pr, min(pg, pb))
                        darkSmall[dOff + sx] = dc
                        val luma = 0.299f * pr + 0.587f * pg + 0.114f * pb
                        if (dc > 0.45f && luma > maxBright) {
                            maxBright = luma
                            atmR = pr.coerceIn(0.75f, 0.98f)
                            atmG = pg.coerceIn(0.75f, 0.98f)
                            atmB = pb.coerceIn(0.75f, 0.98f)
                        }
                    }
                }
                smoothDark = separableBoxBlurFloat(darkSmall, dw, dh, radius = 3, passes = 2)
            }

            fun applyDehazeToPixel(px: Int, gx: Int, gy: Int): Int {
                val a = px and 0xFF000000.toInt()
                var r = ((px ushr 16) and 0xFF) * inv255
                var g = ((px ushr 8) and 0xFF) * inv255
                var b = (px and 0xFF) * inv255
                if (dehazeVal > 0f && smoothDark != null) {
                    val dwM1 = (dw - 1).coerceAtLeast(1)
                    val dhM1 = (dh - 1).coerceAtLeast(1)
                    val fx = (gx.toFloat() / (w - 1).coerceAtLeast(1)) * dwM1
                    val fy = (gy.toFloat() / (h - 1).coerceAtLeast(1)) * dhM1
                    val sx0 = fx.toInt().coerceIn(0, dwM1); val sx1 = (sx0 + 1).coerceAtMost(dwM1)
                    val sy0 = fy.toInt().coerceIn(0, dhM1); val sy1 = (sy0 + 1).coerceAtMost(dhM1)
                    val wx = fx - sx0; val wy = fy - sy0
                    val d00 = smoothDark[sy0 * dw + sx0]; val d10 = smoothDark[sy0 * dw + sx1]
                    val d01 = smoothDark[sy1 * dw + sx0]; val d11 = smoothDark[sy1 * dw + sx1]
                    val veil = (d00 * (1f - wx) + d10 * wx) * (1f - wy) + (d01 * (1f - wx) + d11 * wx) * wy
                    val t = (1f - dehazeVal * 0.72f * veil).coerceIn(0.32f, 1f)
                    r = ((r - atmR * (1f - t)) / t).coerceIn(0f, 1f)
                    g = ((g - atmG * (1f - t)) / t).coerceIn(0f, 1f)
                    b = ((b - atmB * (1f - t)) / t).coerceIn(0f, 1f)
                } else if (dehazeVal < 0f) {
                    val mist = (-dehazeVal) * 0.42f
                    r = (r * (1f - mist) + atmR * mist).coerceIn(0f, 1f)
                    g = (g * (1f - mist) + atmG * mist).coerceIn(0f, 1f)
                    b = (b * (1f - mist) + atmB * mist).coerceIn(0f, 1f)
                }
                return a or
                    (((r * 255f + 0.5f).toInt().coerceIn(0, 255)) shl 16) or
                    (((g * 255f + 0.5f).toInt().coerceIn(0, 255)) shl 8) or
                    ((b * 255f + 0.5f).toInt().coerceIn(0, 255))
            }

            var lowLuma: FloatArray? = null
            var lw = 0; var lh = 0
            if (doClarity) {
                val maxLow = 144
                val sc = maxLow.toFloat() / max(w, h).toFloat()
                lw = (w * sc).toInt().coerceIn(24, min(w, maxLow))
                lh = (h * sc).toInt().coerceIn(24, min(h, maxLow))
                val small = FloatArray(lw * lh)
                val tmp = FloatArray(3)
                for (sy in 0 until lh) {
                    val y0 = ((sy.toLong() * h) / lh).toInt().coerceIn(0, h - 1)
                    val dOff = sy * lw
                    for (sx in 0 until lw) {
                        val x0 = ((sx.toLong() * w) / lw).toInt().coerceIn(0, w - 1)
                        var px = transformPixelStages0to5(source.getPixel(x0, y0), tmp)
                        if (doDehaze) px = applyDehazeToPixel(px, x0, y0)
                        val pr = ((px ushr 16) and 0xFF) * inv255
                        val pg = ((px ushr 8) and 0xFF) * inv255
                        val pb = (px and 0xFF) * inv255
                        small[dOff + sx] = 0.299f * pr + 0.587f * pg + 0.114f * pb
                    }
                }
                lowLuma = separableBoxBlurFloat(small, lw, lh, radius = 3, passes = 2)
            }

            // Allocate reusable 256-row (+2 halo for 3x3 Sharpen) strip buffers (~7.5MB IntArray + ~7.5MB FloatArray!)
            val stripCoreRows = 256.coerceAtMost(h.coerceAtLeast(1))
            val haloPad = if (doSharpen) 1 else 0
            val maxBufRows = (stripCoreRows + haloPad * 2).coerceAtMost(h)
            val stripPixels = IntArray(w * maxBufRows)
            val stripLuma = if (doClarity || doSharpen) FloatArray(w * maxBufRows) else null

            val lwM1 = (lw - 1).coerceAtLeast(1)
            val lhM1 = (lh - 1).coerceAtLeast(1)
            val clarifyGain = clarityVal * 0.65f
            val sharpGain = sharpenVal * 1.15f
            val halfW = (w * 0.5f).coerceAtLeast(1f)
            val halfH = (h * 0.5f).coerceAtLeast(1f)

            var yStart = 0
            while (yStart < h) {
                val yEnd = min(h, yStart + stripCoreRows)
                val coreH = yEnd - yStart
                val hy0 = (yStart - haloPad).coerceAtLeast(0)
                val hy1 = (yEnd + haloPad).coerceAtMost(h)
                val bufH = hy1 - hy0
                val localCoreStart = yStart - hy0
                val localCoreEnd = localCoreStart + coreH

                source.getPixels(stripPixels, 0, w, 0, hy0, w, bufH)

                // Pass A: Per-pixel Stages 0..5 + Dehaze + Luma plane fill across [0, bufH)
                fun runStageA(ly0: Int, ly1: Int) {
                    val tmpRgb = FloatArray(3)
                    for (ly in ly0 until ly1) {
                        val gy = hy0 + ly
                        val row = ly * w
                        for (x in 0 until w) {
                            val idx = row + x
                            var px = transformPixelStages0to5(stripPixels[idx], tmpRgb)
                            if (doDehaze) px = applyDehazeToPixel(px, x, gy)
                            stripPixels[idx] = px
                            if (stripLuma != null) {
                                val r = ((px ushr 16) and 0xFF) * inv255
                                val g = ((px ushr 8) and 0xFF) * inv255
                                val b = (px and 0xFF) * inv255
                                stripLuma[idx] = 0.299f * r + 0.587f * g + 0.114f * b
                            }
                        }
                    }
                }

                if (bufH >= 48 && w * bufH >= 200_000) {
                    val chunks = 4
                    val step = (bufH + chunks - 1) / chunks
                    coroutineScope {
                        (0 until chunks).map { c ->
                            val r0 = c * step
                            val r1 = min(bufH, r0 + step)
                            async(Dispatchers.Default) { if (r0 < r1) runStageA(r0, r1) }
                        }.awaitAll()
                    }
                } else {
                    runStageA(0, bufH)
                }

                // Pass B: Clarity + 3x3 Sharpen + Optical Vignette strictly on core rows [localCoreStart, localCoreEnd)
                if (doClarity || doSharpen || doVignette) {
                    fun runStageB(ly0: Int, ly1: Int) {
                        for (ly in ly0 until ly1) {
                            val gy = hy0 + ly
                            val row = ly * w
                            val rowUp = (ly - 1).coerceAtLeast(0) * w
                            val rowDn = (ly + 1).coerceAtMost(bufH - 1) * w
                            val fy = if (doClarity) (gy.toFloat() / (h - 1).coerceAtLeast(1)) * lhM1 else 0f
                            val sy0 = fy.toInt().coerceIn(0, lhM1)
                            val sy1 = (sy0 + 1).coerceAtMost(lhM1)
                            val wy = fy - sy0
                            val dy = (gy - halfH) / halfH
                            val dy2 = dy * dy

                            for (x in 0 until w) {
                                val idx = row + x
                                var px = stripPixels[idx]
                                val a = px and 0xFF000000.toInt()
                                var r = ((px ushr 16) and 0xFF) * inv255
                                var g = ((px ushr 8) and 0xFF) * inv255
                                var b = (px and 0xFF) * inv255
                                var changed = false

                                if ((doClarity || doSharpen) && stripLuma != null) {
                                    val lCenter = stripLuma[idx]
                                    var deltaL = 0f
                                    if (doClarity && lowLuma != null) {
                                        val fx = (x.toFloat() / (w - 1).coerceAtLeast(1)) * lwM1
                                        val sx0 = fx.toInt().coerceIn(0, lwM1)
                                        val sx1 = (sx0 + 1).coerceAtMost(lwM1)
                                        val wx = fx - sx0
                                        val b00 = lowLuma[sy0 * lw + sx0]; val b10 = lowLuma[sy0 * lw + sx1]
                                        val b01 = lowLuma[sy1 * lw + sx0]; val b11 = lowLuma[sy1 * lw + sx1]
                                        val blurL = (b00 * (1f - wx) + b10 * wx) * (1f - wy) + (b01 * (1f - wx) + b11 * wx) * wy
                                        val midWeight = (4f * lCenter * (1f - lCenter)).coerceIn(0f, 1f)
                                        deltaL += (lCenter - blurL) * clarifyGain * midWeight
                                    }
                                    if (doSharpen) {
                                        val xm = (x - 1).coerceAtLeast(0)
                                        val xp = (x + 1).coerceAtMost(w - 1)
                                        val localAvg = (
                                            lCenter * 4f +
                                                (stripLuma[row + xm] + stripLuma[row + xp] + stripLuma[rowUp + x] + stripLuma[rowDn + x]) * 2f +
                                                (stripLuma[rowUp + xm] + stripLuma[rowUp + xp] + stripLuma[rowDn + xm] + stripLuma[rowDn + xp])
                                            ) * 0.0625f
                                        val hp = lCenter - localAvg
                                        if (abs(hp) > 0.007f) deltaL += hp * sharpGain
                                    }
                                    if (abs(deltaL) > 0.0005f) {
                                        val lNew = (lCenter + deltaL).coerceIn(0f, 1f)
                                        if (lCenter > 0.004f) {
                                            val ratio = (lNew / lCenter).coerceIn(0.45f, 1.85f)
                                            r = (r * ratio).coerceIn(0f, 1f)
                                            g = (g * ratio).coerceIn(0f, 1f)
                                            b = (b * ratio).coerceIn(0f, 1f)
                                        } else {
                                            r = (r + deltaL).coerceIn(0f, 1f)
                                            g = (g + deltaL).coerceIn(0f, 1f)
                                            b = (b + deltaL).coerceIn(0f, 1f)
                                        }
                                        changed = true
                                    }
                                }

                                if (doVignette) {
                                    val dx = (x - halfW) / halfW
                                    val dist = sqrt(dx * dx + dy2)
                                    val falloff = smoothstep(0.45f, 1.42f, dist)
                                    if (falloff > 0.001f) {
                                        if (vignetteVal < 0f) {
                                            val strength = (-vignetteVal) * 0.72f * falloff * falloff
                                            val luma = 0.299f * r + 0.587f * g + 0.114f * b
                                            val gain = (1f - strength * (1f - 0.28f * luma * luma)).coerceIn(0.18f, 1f)
                                            r *= gain; g *= gain; b *= gain
                                        } else {
                                            val lift = vignetteVal * 0.52f * falloff * falloff
                                            r = (r + (1f - r) * lift).coerceIn(0f, 1f)
                                            g = (g + (1f - g) * lift).coerceIn(0f, 1f)
                                            b = (b + (1f - b) * lift).coerceIn(0f, 1f)
                                        }
                                        changed = true
                                    }
                                }

                                if (changed) {
                                    stripPixels[idx] = a or
                                        (((r * 255f + 0.5f).toInt().coerceIn(0, 255)) shl 16) or
                                        (((g * 255f + 0.5f).toInt().coerceIn(0, 255)) shl 8) or
                                        ((b * 255f + 0.5f).toInt().coerceIn(0, 255))
                                }
                            }
                        }
                    }

                    if (coreH >= 48 && w * coreH >= 200_000) {
                        val chunks = 4
                        val step = (coreH + chunks - 1) / chunks
                        coroutineScope {
                            (0 until chunks).map { c ->
                                val r0 = localCoreStart + c * step
                                val r1 = min(localCoreEnd, r0 + step)
                                async(Dispatchers.Default) { if (r0 < r1) runStageB(r0, r1) }
                            }.awaitAll()
                        }
                    } else {
                        runStageB(localCoreStart, localCoreEnd)
                    }
                }

                outBitmap.setPixels(stripPixels, localCoreStart * w, w, 0, yStart, w, coreH)
                yStart = yEnd
            }
            outBitmap
        } else {
            source.copy(Bitmap.Config.ARGB_8888, true)
        }

        if (showVintageDate) {
            drawGlowingVintageDate(out, dateStringRaw)
        }
        out
    }

    private fun separableBoxBlurFloat(src: FloatArray, w: Int, h: Int, radius: Int, passes: Int): FloatArray {
        val cur = src.copyOf()
        val tmp = FloatArray(w * h)
        val r = radius.coerceAtLeast(1)
        repeat(passes) {
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    var sum = 0f
                    var cnt = 0
                    val x0 = (x - r).coerceAtLeast(0)
                    val x1 = (x + r).coerceAtMost(w - 1)
                    for (kx in x0..x1) {
                        sum += cur[row + kx]
                        cnt++
                    }
                    tmp[row + x] = sum / cnt
                }
            }
            for (x in 0 until w) {
                for (y in 0 until h) {
                    var sum = 0f
                    var cnt = 0
                    val y0 = (y - r).coerceAtLeast(0)
                    val y1 = (y + r).coerceAtMost(h - 1)
                    for (ky in y0..y1) {
                        sum += tmp[ky * w + x]
                        cnt++
                    }
                    cur[y * w + x] = sum / cnt
                }
            }
        }
        return cur
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
            maskFilter = BlurMaskFilter((textSize * 0.28f).coerceIn(2f, 32f), BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawText(stamp, x, y, outerGlow)

        val innerGlow = Paint(outerGlow).apply {
            color = Color.argb(225, 255, 118, 28)
            maskFilter = BlurMaskFilter((textSize * 0.09f).coerceIn(1f, 14f), BlurMaskFilter.Blur.NORMAL)
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

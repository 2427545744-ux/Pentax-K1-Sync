package com.pentax.k1sync.image

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.math.floor
import kotlin.math.pow

data class LutPreset(
    val id: String,
    val name: String,
    val subtitle: String,
    val isCustomFile: Boolean = false,
    val filePath: String? = null
)

data class CubeLut3D(
    val title: String,
    val size: Int,
    val domainMin: FloatArray = floatArrayOf(0f, 0f, 0f),
    val domainMax: FloatArray = floatArrayOf(1f, 1f, 1f),
    // Flattened RGB triplets in standard .cube order (R varies fastest, then G, then B)
    val table: FloatArray
)

/**
 * High-performance 3D LUT (.cube) parser, persistent library manager, and trilinear color grading engine.
 * Supports importing any standard 17^3 / 33^3 / 65^3 .cube LUT file from Android storage
 * plus 5 built-in Pentax/film curated 3D LUTs.
 */
object CubeLutEngine {

    const val ID_NONE = "builtin_none"
    const val ID_PENTAX_POSITIVE = "builtin_pentax_positive"
    const val ID_CLASSIC_CHROME = "builtin_classic_chrome"
    const val ID_KODAK_GOLD = "builtin_kodak_gold"
    const val ID_TEAL_ORANGE = "builtin_teal_orange"
    const val ID_MONOCHROME_HC = "builtin_monochrome_hc"

    val BUILTIN_PRESETS: List<LutPreset> = listOf(
        LutPreset(ID_NONE, "原色直出", "无滤镜"),
        LutPreset(ID_PENTAX_POSITIVE, "宾得反转", "浓郁绿蓝"),
        LutPreset(ID_CLASSIC_CHROME, "经典正片", "纪实低饱"),
        LutPreset(ID_KODAK_GOLD, "柯达暖金", "胶片暖调"),
        LutPreset(ID_TEAL_ORANGE, "青橙电影", "暗青亮暖"),
        LutPreset(ID_MONOCHROME_HC, "暗房黑白", "银盐高反差")
    )

    private val lutMemoryCache = HashMap<String, CubeLut3D>()

    private fun lutDir(context: Context): File =
        File(context.filesDir, "custom_luts").apply { mkdirs() }

    fun listAllPresets(context: Context): List<LutPreset> {
        val customFiles = lutDir(context).listFiles { f ->
            f.isFile && f.name.endsWith(".cube", ignoreCase = true)
        }?.sortedByDescending { it.lastModified() }.orEmpty()

        val customs = customFiles.map { file ->
            val displayName = file.nameWithoutExtension
                .removePrefix("lut_")
                .take(18)
            LutPreset(
                id = "custom_${file.name}",
                name = displayName.ifBlank { "自定义 LUT" },
                subtitle = ".cube",
                isCustomFile = true,
                filePath = file.absolutePath
            )
        }
        return BUILTIN_PRESETS + customs
    }

    suspend fun importCubeFile(context: Context, uri: Uri): Result<LutPreset> = withContext(Dispatchers.IO) {
        runCatching {
            val rawName = resolveDisplayName(context, uri)
                ?.substringBeforeLast('.')
                ?.replace(Regex("[^\\p{L}\\p{N}_\\- ]"), "")
                ?.trim()
                ?.take(28)
                .orEmpty()
                .ifBlank { "CustomLUT_${System.currentTimeMillis() % 10000}" }

            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("无法读取该 LUT 文件")
            if (bytes.size > 16 * 1024 * 1024) {
                error("LUT 文件过大（超过 16MB），请选择 17x 或 33x 规格的 .cube 文件")
            }

            // Validate syntax before saving
            val parsed = parseCubeBytes(bytes, rawName)
            val safeFileName = "${rawName}_${System.currentTimeMillis() % 10000}.cube"
            val outFile = File(lutDir(context), safeFileName)
            outFile.writeBytes(bytes)

            val preset = LutPreset(
                id = "custom_${outFile.name}",
                name = parsed.title.ifBlank { rawName }.take(18),
                subtitle = "${parsed.size}³ .cube",
                isCustomFile = true,
                filePath = outFile.absolutePath
            )
            synchronized(lutMemoryCache) {
                lutMemoryCache[preset.id] = parsed
            }
            preset
        }
    }

    fun deleteCustomLut(preset: LutPreset): Boolean {
        if (!preset.isCustomFile || preset.filePath.isNullOrBlank()) return false
        synchronized(lutMemoryCache) {
            lutMemoryCache.remove(preset.id)
        }
        return try {
            File(preset.filePath).delete()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Memory-safe, 4-core parallel 3D LUT trilinear renderer using 256-row horizontal strip streaming.
     * Peak Java heap on a 36.4MP (7360x4912) photo is ~7.5 MB instead of 145 MB — zero OOM crashes!
     */
    suspend fun applyLut(
        source: Bitmap,
        preset: LutPreset,
        intensity: Float
    ): Bitmap = withContext(Dispatchers.Default) {
        val alpha = intensity.coerceIn(0f, 1f)
        if (preset.id == ID_NONE || alpha <= 0.005f) {
            return@withContext source
        }

        val lut = loadOrBuildLut(preset) ?: return@withContext source
        val w = source.width
        val h = source.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

        val size = lut.size
        val maxIdx = (size - 1).toFloat()
        val sizeSq = size * size
        val table = lut.table
        val dMinR = lut.domainMin[0]; val dSpanR = (lut.domainMax[0] - dMinR).coerceAtLeast(1e-5f)
        val dMinG = lut.domainMin[1]; val dSpanG = (lut.domainMax[1] - dMinG).coerceAtLeast(1e-5f)
        val dMinB = lut.domainMin[2]; val dSpanB = (lut.domainMax[2] - dMinB).coerceAtLeast(1e-5f)
        val inv255 = 1f / 255f
        val invAlpha = 1f - alpha

        val stripRows = 256.coerceAtMost(h.coerceAtLeast(1))
        val stripBuf = IntArray(w * stripRows)

        fun processRange(startIdx: Int, endIdx: Int) {
            for (i in startIdx until endIdx) {
                val px = stripBuf[i]
                val a = px and 0xFF000000.toInt()
                val r0 = ((px ushr 16) and 0xFF) * inv255
                val g0 = ((px ushr 8) and 0xFF) * inv255
                val b0 = (px and 0xFF) * inv255

                val rn = ((r0 - dMinR) / dSpanR).coerceIn(0f, 1f) * maxIdx
                val gn = ((g0 - dMinG) / dSpanG).coerceIn(0f, 1f) * maxIdx
                val bn = ((b0 - dMinB) / dSpanB).coerceIn(0f, 1f) * maxIdx

                val rLo = floor(rn).toInt().coerceIn(0, size - 1)
                val gLo = floor(gn).toInt().coerceIn(0, size - 1)
                val bLo = floor(bn).toInt().coerceIn(0, size - 1)
                val rHi = (rLo + 1).coerceAtMost(size - 1)
                val gHi = (gLo + 1).coerceAtMost(size - 1)
                val bHi = (bLo + 1).coerceAtMost(size - 1)

                val fr = rn - rLo
                val fg = gn - gLo
                val fb = bn - bLo

                val i000 = (bLo * sizeSq + gLo * size + rLo) * 3
                val i100 = (bLo * sizeSq + gLo * size + rHi) * 3
                val i010 = (bLo * sizeSq + gHi * size + rLo) * 3
                val i110 = (bLo * sizeSq + gHi * size + rHi) * 3
                val i001 = (bHi * sizeSq + gLo * size + rLo) * 3
                val i101 = (bHi * sizeSq + gLo * size + rHi) * 3
                val i011 = (bHi * sizeSq + gHi * size + rLo) * 3
                val i111 = (bHi * sizeSq + gHi * size + rHi) * 3

                val c00r = table[i000] + (table[i100] - table[i000]) * fr
                val c10r = table[i010] + (table[i110] - table[i010]) * fr
                val c01r = table[i001] + (table[i101] - table[i001]) * fr
                val c11r = table[i011] + (table[i111] - table[i011]) * fr
                val lr = (c00r + (c10r - c00r) * fg) * (1f - fb) + (c01r + (c11r - c01r) * fg) * fb

                val c00g = table[i000 + 1] + (table[i100 + 1] - table[i000 + 1]) * fr
                val c10g = table[i010 + 1] + (table[i110 + 1] - table[i010 + 1]) * fr
                val c01g = table[i001 + 1] + (table[i101 + 1] - table[i001 + 1]) * fr
                val c11g = table[i011 + 1] + (table[i111 + 1] - table[i011 + 1]) * fr
                val lg = (c00g + (c10g - c00g) * fg) * (1f - fb) + (c01g + (c11g - c01g) * fg) * fb

                val c00b = table[i000 + 2] + (table[i100 + 2] - table[i000 + 2]) * fr
                val c10b = table[i010 + 2] + (table[i110 + 2] - table[i010 + 2]) * fr
                val c01b = table[i001 + 2] + (table[i101 + 2] - table[i001 + 2]) * fr
                val c11b = table[i011 + 2] + (table[i111 + 2] - table[i011 + 2]) * fr
                val lb = (c00b + (c10b - c00b) * fg) * (1f - fb) + (c01b + (c11b - c01b) * fg) * fb

                val rOut = (r0 * invAlpha + lr * alpha).coerceIn(0f, 1f)
                val gOut = (g0 * invAlpha + lg * alpha).coerceIn(0f, 1f)
                val bOut = (b0 * invAlpha + lb * alpha).coerceIn(0f, 1f)

                val ri = (rOut * 255f + 0.5f).toInt().coerceIn(0, 255)
                val gi = (gOut * 255f + 0.5f).toInt().coerceIn(0, 255)
                val bi = (bOut * 255f + 0.5f).toInt().coerceIn(0, 255)
                stripBuf[i] = a or (ri shl 16) or (gi shl 8) or bi
            }
        }

        var y0 = 0
        while (y0 < h) {
            val curH = minOf(stripRows, h - y0)
            val count = w * curH
            source.getPixels(stripBuf, 0, w, 0, y0, w, curH)
            if (count >= 250_000) {
                val chunks = 4
                val step = (count + chunks - 1) / chunks
                coroutineScope {
                    (0 until chunks).map { c ->
                        val s = c * step
                        val e = minOf(count, s + step)
                        async(Dispatchers.Default) {
                            if (s < e) processRange(s, e)
                        }
                    }.awaitAll()
                }
            } else {
                processRange(0, count)
            }
            out.setPixels(stripBuf, 0, w, 0, y0, w, curH)
            y0 += curH
        }
        out
    }

    private fun loadOrBuildLut(preset: LutPreset): CubeLut3D? {
        synchronized(lutMemoryCache) {
            lutMemoryCache[preset.id]?.let { return it }
        }
        val built = if (preset.isCustomFile && !preset.filePath.isNullOrBlank()) {
            try {
                val file = File(preset.filePath)
                if (!file.exists()) return null
                parseCubeBytes(file.readBytes(), preset.name)
            } catch (_: Exception) {
                return null
            }
        } else {
            buildProceduralPresetLut(preset.id)
        } ?: return null

        synchronized(lutMemoryCache) {
            lutMemoryCache[preset.id] = built
        }
        return built
    }

    fun parseCubeBytes(bytes: ByteArray, fallbackTitle: String): CubeLut3D {
        val reader = BufferedReader(InputStreamReader(bytes.inputStream(), Charsets.UTF_8))
        var title = fallbackTitle
        var size = -1
        val domainMin = floatArrayOf(0f, 0f, 0f)
        val domainMax = floatArrayOf(1f, 1f, 1f)
        var table: FloatArray? = null
        var cursor = 0

        reader.forEachLine { rawLine ->
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) return@forEachLine

            val upper = line.uppercase()
            when {
                upper.startsWith("TITLE") -> {
                    val t = line.drop(5).trim().trim('"')
                    if (t.isNotBlank()) title = t
                }
                upper.startsWith("LUT_3D_SIZE") -> {
                    val n = line.split(Regex("\\s+")).getOrNull(1)?.toIntOrNull()
                        ?: error("无效的 LUT_3D_SIZE")
                    if (n < 2 || n > 66) error("不支持的 3D LUT 尺寸: $n（支持 2~65）")
                    size = n
                    table = FloatArray(n * n * n * 3)
                }
                upper.startsWith("LUT_1D_SIZE") -> {
                    error("暂不支持 1D LUT，请导入标准 3D .cube 文件（含 LUT_3D_SIZE）")
                }
                upper.startsWith("DOMAIN_MIN") -> {
                    val p = line.split(Regex("\\s+"))
                    if (p.size >= 4) {
                        domainMin[0] = p[1].toFloatOrNull() ?: 0f
                        domainMin[1] = p[2].toFloatOrNull() ?: 0f
                        domainMin[2] = p[3].toFloatOrNull() ?: 0f
                    }
                }
                upper.startsWith("DOMAIN_MAX") -> {
                    val p = line.split(Regex("\\s+"))
                    if (p.size >= 4) {
                        domainMax[0] = p[1].toFloatOrNull() ?: 1f
                        domainMax[1] = p[2].toFloatOrNull() ?: 1f
                        domainMax[2] = p[3].toFloatOrNull() ?: 1f
                    }
                }
                else -> {
                    val arr = table ?: return@forEachLine
                    if (cursor + 2 >= arr.size) return@forEachLine
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size >= 3) {
                        val r = parts[0].toFloatOrNull()
                        val g = parts[1].toFloatOrNull()
                        val b = parts[2].toFloatOrNull()
                        if (r != null && g != null && b != null) {
                            arr[cursor++] = r.coerceIn(0f, 1f)
                            arr[cursor++] = g.coerceIn(0f, 1f)
                            arr[cursor++] = b.coerceIn(0f, 1f)
                        }
                    }
                }
            }
        }

        val finalTable = table ?: error("未找到 LUT_3D_SIZE 声明，请确认是有效的 3D .cube 文件")
        if (cursor < finalTable.size) {
            error("LUT 数据不完整：期望 ${finalTable.size / 3} 行，实际仅读取 ${cursor / 3} 行")
        }
        return CubeLut3D(title = title, size = size, domainMin = domainMin, domainMax = domainMax, table = finalTable)
    }

    /**
     * Generates authentic 17x17x17 procedural 3D LUTs for instant built-in film grading.
     */
    private fun buildProceduralPresetLut(id: String): CubeLut3D? {
        val n = 17
        val table = FloatArray(n * n * n * 3)
        val inv = 1f / (n - 1)
        var idx = 0

        fun sCurve(v: Float, strength: Float): Float {
            val c = v.coerceIn(0f, 1f)
            val curved = c * c * (3f - 2f * c)
            return c * (1f - strength) + curved * strength
        }

        for (bi in 0 until n) {
            val b0 = bi * inv
            for (gi in 0 until n) {
                val g0 = gi * inv
                for (ri in 0 until n) {
                    val r0 = ri * inv
                    val luma = 0.299f * r0 + 0.587f * g0 + 0.114f * b0

                    var r = r0
                    var g = g0
                    var b = b0

                    when (id) {
                        ID_PENTAX_POSITIVE -> {
                            // Pentax Reversal/Positive Film: deep crisp shadows, rich emerald greens & cobalt skies
                            r = sCurve(r0, 0.34f)
                            g = sCurve(g0, 0.32f)
                            b = sCurve(b0, 0.38f)
                            val sat = 1.22f
                            r = luma + (r - luma) * sat
                            g = luma + (g - luma) * (sat + 0.08f)
                            b = luma + (b - luma) * (sat + 0.05f)
                            // Deepen blue shadows slightly
                            b += (1f - luma) * 0.03f
                            r -= (1f - luma) * 0.015f
                        }
                        ID_CLASSIC_CHROME -> {
                            // Documentary Classic Chrome: lifted matte blacks, muted reds/blues, warm ivory highlights
                            r = sCurve(r0, 0.28f) * 0.96f + 0.025f
                            g = sCurve(g0, 0.28f) * 0.96f + 0.022f
                            b = sCurve(b0, 0.30f) * 0.94f + 0.028f
                            val sat = 0.78f
                            r = luma + (r - luma) * sat + luma * 0.025f
                            g = luma + (g - luma) * sat + luma * 0.012f
                            b = luma + (b - luma) * (sat + 0.06f) - luma * 0.02f
                        }
                        ID_KODAK_GOLD -> {
                            // Kodak Gold 200: warm amber midtones, gently faded dark teal blacks
                            r = (r0.pow(0.92f)) * 0.96f + 0.035f
                            g = (g0.pow(0.97f)) * 0.95f + 0.025f
                            b = (b0.pow(1.08f)) * 0.88f + 0.045f
                            r += (1f - kotlin.math.abs(luma - 0.55f) * 2f).coerceAtLeast(0f) * 0.045f
                            g += (1f - kotlin.math.abs(luma - 0.55f) * 2f).coerceAtLeast(0f) * 0.02f
                        }
                        ID_TEAL_ORANGE -> {
                            // Cinema Teal & Orange split toning
                            val cr = sCurve(r0, 0.35f)
                            val cg = sCurve(g0, 0.32f)
                            val cb = sCurve(b0, 0.35f)
                            val shadowW = (1f - luma).pow(1.6f)
                            val highW = luma.pow(1.3f)
                            r = cr - shadowW * 0.09f + highW * 0.085f
                            g = cg + shadowW * 0.025f + highW * 0.025f
                            b = cb + shadowW * 0.11f - highW * 0.085f
                        }
                        ID_MONOCHROME_HC -> {
                            // Silver-gelatin red-filter monochrome (dramatic dark skies, bright skin/stone)
                            val mono = (0.46f * r0 + 0.44f * g0 + 0.10f * b0).coerceIn(0f, 1f)
                            val contrasted = sCurve(mono, 0.62f) * 0.97f + 0.012f
                            r = contrasted
                            g = contrasted
                            b = contrasted * 1.015f // crisp silver cool highlight tint
                        }
                        else -> return null
                    }

                    table[idx++] = r.coerceIn(0f, 1f)
                    table[idx++] = g.coerceIn(0f, 1f)
                    table[idx++] = b.coerceIn(0f, 1f)
                }
            }
        }
        return CubeLut3D(title = id, size = n, table = table)
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (_: Exception) {
            null
        }
    }
}

package com.pentax.k1sync.spektra

import android.content.Context
import com.pentax.k1sync.image.PostToneConfig
import org.json.JSONObject
import java.io.File
import kotlin.math.pow

enum class FilmCategory(val label: String) {
    COLOR_NEG("彩负"),
    CINE("电影"),
    SLIDE("反转正片"),
    BW("黑白")
}

enum class FilmFormatSpec(val label: String, val widthMm: Float, val desc: String) {
    FORMAT_135("135 (36×24)", 36f, "经典全画幅颗粒与光晕尺度"),
    FORMAT_645("120 (6×4.5)", 56f, "中画幅细腻过渡"),
    FORMAT_6X7("120 (6×7)", 70f, "大底细腻银盐质感"),
    FORMAT_6X9("120 (6×9)", 84f, "极致微细颗粒")
}

enum class DiffusionFilterFamily(val apiName: String, val label: String) {
    OFF("off", "关闭"),
    BLACK_PRO_MIST("black_pro_mist", "黑柔 BPM"),
    PRO_MIST("pro_mist", "白柔 Mist"),
    GLIMMERGLASS("glimmerglass", "星光柔焦"),
    CINEBLOOM("cinebloom", "电影柔光")
}

enum class LightLeakKind(val apiName: String, val label: String) {
    OFF("off", "关闭"),
    AUTO("auto", "自然随机"),
    BAND("band", "条纹漏光"),
    WASH("wash", "柔和漫射"),
    SPOT("spot", "边缘光斑")
}

enum class BwCameraFilter(val apiName: String, val label: String, val swatchHex: Long) {
    NONE("", "无色镜", 0xFF6B7280),
    W8("w8", "黄镜 #8", 0xFFFACC15),
    W12("w12", "深黄 #12", 0xFFEAB308),
    W21("w21", "橙镜 #21", 0xFFF97316),
    W25("w25", "红镜 #25", 0xFFEF4444),
    W11("w11", "黄绿 #11", 0xFF84CC16),
    W58("w58", "绿镜 #58", 0xFF22C55E)
}

data class SpektraFilmStock(
    val id: String,
    val shortName: String,
    val fullName: String,
    val category: FilmCategory,
    val iso: Int,
    val defaultPrintPaperId: String?,
    val swatchHex: Long
) {
    val isSlide: Boolean get() = category == FilmCategory.SLIDE
    val isBw: Boolean get() = category == FilmCategory.BW
}

data class SpektraPrintPaper(
    val id: String,
    val shortName: String,
    val isBw: Boolean = false
)

data class SpektraLatitudeReadout(
    val shadowEv: Double,
    val highlightEv: Double,
    val retainedStops: Double,
    val sceneP01: Double,
    val sceneP999: Double,
    val suggestedHighlightPullBack: Double,
    val suggestedShadowPullBack: Double,
    val fitValid: Boolean,
    val fitParamsDeltaJson: String?,
    val histogram: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SpektraLatitudeReadout) return false
        return shadowEv == other.shadowEv && highlightEv == other.highlightEv && retainedStops == other.retainedStops
    }

    override fun hashCode(): Int = shadowEv.hashCode() * 31 + highlightEv.hashCode()
}

data class SpektraDarkroomParams(
    val filmStockId: String = "kodak_portra_400",
    val printStockId: String = "kodak_portra_endura",
    val scanFilm: Boolean = false,
    val filmFormat: FilmFormatSpec = FilmFormatSpec.FORMAT_135,
    val exposureCompEv: Float = 0f,
    val autoExposure: Boolean = true,
    val dirCouplersActive: Boolean = true,
    val dirCouplersAmount: Float = 1.0f,
    val halationActive: Boolean = true,
    val halationAmount: Float = 1.0f,
    val grainActive: Boolean = true,
    val grainAmount: Float = 1.0f,
    val printBrightnessEv: Float = 0.0f,
    val cFilterNeutral: Float? = null,
    val mFilterNeutral: Float? = null,
    val yFilterNeutral: Float? = null,
    val mFilterShift: Float = 0.0f,
    val yFilterShift: Float = 0.0f,
    val glareActive: Boolean = true,
    val glareAmount: Float = 1.0f,
    val cameraFilter: BwCameraFilter = BwCameraFilter.NONE,
    val diffusionFamily: DiffusionFilterFamily = DiffusionFilterFamily.OFF,
    val diffusionStrength: Float = 0.65f,
    val leakKind: LightLeakKind = LightLeakKind.OFF,
    val leakAmount: Float = 1.0f,
    val leakSeed: Int = 42,
    val sceneLatitudeActive: Boolean = false,
    val sceneLatitudeDeltaJson: String? = null,
    val rawCustomWb: Boolean = false,
    val rawTempKelvin: Float = 5500f,
    val rawTint: Float = 0f
) {
    companion object {
        const val FILTER_SHIFT_CC_SCALE = 25.0

        fun printBrightnessToWireExposure(brightnessStops: Float): Double =
            2.0.pow(-brightnessStops.toDouble()).coerceIn(0.05, 20.0)

        fun fromStorageJson(obj: JSONObject): SpektraDarkroomParams {
            val fmt = FilmFormatSpec.entries.firstOrNull { it.name == obj.optString("filmFormat") } ?: FilmFormatSpec.FORMAT_135
            val diff = DiffusionFilterFamily.entries.firstOrNull { it.name == obj.optString("diffusionFamily") } ?: DiffusionFilterFamily.OFF
            val leak = LightLeakKind.entries.firstOrNull { it.name == obj.optString("leakKind") } ?: LightLeakKind.OFF
            val cf = BwCameraFilter.entries.firstOrNull { it.name == obj.optString("cameraFilter") } ?: BwCameraFilter.NONE
            val brightnessEv = if (obj.has("printBrightnessEv")) {
                obj.optDouble("printBrightnessEv", 0.0).toFloat()
            } else {
                val pe = obj.optDouble("printExposure", 1.0).coerceIn(0.125, 8.0)
                (-kotlin.math.ln(pe) / kotlin.math.ln(2.0)).toFloat().coerceIn(-2.5f, 2.5f)
            }
            return SpektraDarkroomParams(
                filmStockId = obj.optString("filmStockId", "kodak_portra_400"),
                printStockId = obj.optString("printStockId", "kodak_portra_endura"),
                scanFilm = obj.optBoolean("scanFilm", false),
                filmFormat = fmt,
                exposureCompEv = obj.optDouble("exposureCompEv", 0.0).toFloat(),
                autoExposure = obj.optBoolean("autoExposure", true),
                dirCouplersActive = obj.optBoolean("dirCouplersActive", true),
                dirCouplersAmount = obj.optDouble("dirCouplersAmount", 1.0).toFloat().coerceIn(0f, 1.6f),
                halationActive = obj.optBoolean("halationActive", true),
                halationAmount = obj.optDouble("halationAmount", 1.0).toFloat(),
                grainActive = obj.optBoolean("grainActive", true),
                grainAmount = obj.optDouble("grainAmount", 1.0).toFloat(),
                printBrightnessEv = brightnessEv,
                mFilterShift = obj.optDouble("mFilterShift", 0.0).toFloat().coerceIn(-1f, 1f),
                yFilterShift = obj.optDouble("yFilterShift", 0.0).toFloat().coerceIn(-1f, 1f),
                cameraFilter = cf,
                diffusionFamily = diff,
                diffusionStrength = obj.optDouble("diffusionStrength", 0.65).toFloat(),
                leakKind = leak,
                leakAmount = obj.optDouble("leakAmount", 1.0).toFloat(),
                leakSeed = obj.optInt("leakSeed", 42)
            )
        }
    }

    fun toEngineJson(): String {
        val obj = JSONObject()
        obj.put("film_stock", filmStockId)
        if (!scanFilm && printStockId.isNotBlank()) {
            obj.put("print_stock", printStockId)
        }
        obj.put("scan_film", scanFilm)
        obj.put("film_format_mm", filmFormat.widthMm.toDouble())
        obj.put("exposure_compensation_ev", exposureCompEv.toDouble())
        obj.put("auto_exposure", autoExposure)
        obj.put("camera_filter", cameraFilter.apiName)
        obj.put("dir_couplers_active", dirCouplersActive)
        obj.put("dir_couplers_amount", dirCouplersAmount.coerceIn(0f, 1.6f).toDouble())
        obj.put("halation_active", halationActive)
        obj.put("halation_amount", halationAmount.toDouble())
        obj.put("grain_active", grainActive)
        obj.put("grain_amount", grainAmount.toDouble())

        obj.put("print_exposure", printBrightnessToWireExposure(printBrightnessEv))
        if (cFilterNeutral != null) obj.put("c_filter_neutral", cFilterNeutral.toDouble())
        if (mFilterNeutral != null) obj.put("m_filter_neutral", mFilterNeutral.toDouble())
        if (yFilterNeutral != null) obj.put("y_filter_neutral", yFilterNeutral.toDouble())
        obj.put("m_filter_shift", mFilterShift.coerceIn(-1f, 1f).toDouble())
        obj.put("y_filter_shift", yFilterShift.coerceIn(-1f, 1f).toDouble())
        obj.put("filter_shift_scale", FILTER_SHIFT_CC_SCALE)
        obj.put("glare_active", glareActive)
        obj.put("glare_amount", glareAmount.toDouble())

        val diffOn = diffusionFamily != DiffusionFilterFamily.OFF && diffusionStrength > 0.02f
        obj.put("diffusion_filter_active", diffOn)
        if (diffOn) {
            obj.put("diffusion_filter_family", diffusionFamily.apiName)
            obj.put("diffusion_filter_strength", diffusionStrength.toDouble())
        }

        obj.put("leak_kind", leakKind.apiName)
        if (leakKind != LightLeakKind.OFF) {
            obj.put("leak_amount", leakAmount.toDouble())
            obj.put("leak_rate", 1.0)
            obj.put("leak_seed", leakSeed)
        }

        if (sceneLatitudeActive && !sceneLatitudeDeltaJson.isNullOrBlank()) {
            try {
                val delta = JSONObject(sceneLatitudeDeltaJson)
                val keys = delta.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    obj.put(k, delta.get(k))
                }
            } catch (_: Exception) {
                obj.put("scene_latitude_active", false)
            }
        } else {
            obj.put("scene_latitude_active", false)
        }
        return obj.toString()
    }

    fun toPrintOnlyEngineJson(prev: SpektraDarkroomParams): String {
        val obj = JSONObject()
        if (!scanFilm && printStockId != prev.printStockId && printStockId.isNotBlank()) {
            obj.put("print_stock", printStockId)
        }
        obj.put("print_exposure", printBrightnessToWireExposure(printBrightnessEv))
        obj.put("m_filter_shift", mFilterShift.coerceIn(-1f, 1f).toDouble())
        obj.put("y_filter_shift", yFilterShift.coerceIn(-1f, 1f).toDouble())
        obj.put("filter_shift_scale", FILTER_SHIFT_CC_SCALE)
        if (cFilterNeutral != prev.cFilterNeutral && cFilterNeutral != null) obj.put("c_filter_neutral", cFilterNeutral.toDouble())
        if (mFilterNeutral != prev.mFilterNeutral && mFilterNeutral != null) obj.put("m_filter_neutral", mFilterNeutral.toDouble())
        if (yFilterNeutral != prev.yFilterNeutral && yFilterNeutral != null) obj.put("y_filter_neutral", yFilterNeutral.toDouble())
        if (glareActive != prev.glareActive) obj.put("glare_active", glareActive)
        if (glareAmount != prev.glareAmount) obj.put("glare_amount", glareAmount.toDouble())
        return obj.toString()
    }

    fun isPrintOnlyDeltaFrom(other: SpektraDarkroomParams): Boolean {
        return filmStockId == other.filmStockId &&
            scanFilm == other.scanFilm &&
            filmFormat == other.filmFormat &&
            exposureCompEv == other.exposureCompEv &&
            autoExposure == other.autoExposure &&
            cameraFilter == other.cameraFilter &&
            dirCouplersActive == other.dirCouplersActive &&
            dirCouplersAmount == other.dirCouplersAmount &&
            halationActive == other.halationActive &&
            halationAmount == other.halationAmount &&
            grainActive == other.grainActive &&
            grainAmount == other.grainAmount &&
            diffusionFamily == other.diffusionFamily &&
            diffusionStrength == other.diffusionStrength &&
            leakKind == other.leakKind &&
            leakAmount == other.leakAmount &&
            leakSeed == other.leakSeed &&
            sceneLatitudeActive == other.sceneLatitudeActive &&
            sceneLatitudeDeltaJson == other.sceneLatitudeDeltaJson &&
            rawCustomWb == other.rawCustomWb &&
            rawTempKelvin == other.rawTempKelvin &&
            rawTint == other.rawTint
    }

    fun toStorageJson(): JSONObject = JSONObject().apply {
        put("filmStockId", filmStockId)
        put("printStockId", printStockId)
        put("scanFilm", scanFilm)
        put("filmFormat", filmFormat.name)
        put("exposureCompEv", exposureCompEv.toDouble())
        put("autoExposure", autoExposure)
        put("cameraFilter", cameraFilter.name)
        put("dirCouplersActive", dirCouplersActive)
        put("dirCouplersAmount", dirCouplersAmount.toDouble())
        put("halationActive", halationActive)
        put("halationAmount", halationAmount.toDouble())
        put("grainActive", grainActive)
        put("grainAmount", grainAmount.toDouble())
        put("printBrightnessEv", printBrightnessEv.toDouble())
        put("mFilterShift", mFilterShift.toDouble())
        put("yFilterShift", yFilterShift.toDouble())
        put("diffusionFamily", diffusionFamily.name)
        put("diffusionStrength", diffusionStrength.toDouble())
        put("leakKind", leakKind.name)
        put("leakAmount", leakAmount.toDouble())
        put("leakSeed", leakSeed)
    }
}

data class SavedDarkroomRecipe(
    val id: String,
    val name: String,
    val params: SpektraDarkroomParams,
    val postTone: PostToneConfig = PostToneConfig()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("params", params.toStorageJson())
        if (!postTone.isIdentity) {
            put("postTone", postTone.toJson())
        }
    }

    companion object {
        fun fromJson(obj: JSONObject): SavedDarkroomRecipe? {
            val id = obj.optString("id")
            val name = obj.optString("name")
            val pObj = obj.optJSONObject("params") ?: return null
            if (id.isBlank() || name.isBlank()) return null
            val pt = PostToneConfig.fromJson(obj.optJSONObject("postTone"))
            return SavedDarkroomRecipe(id, name, SpektraDarkroomParams.fromStorageJson(pObj), pt)
        }
    }
}

object SpektraCatalog {

    val PRINT_PAPERS: List<SpektraPrintPaper> = listOf(
        SpektraPrintPaper("kodak_portra_endura", "Portra Endura"),
        SpektraPrintPaper("kodak_supra_endura", "Supra Endura"),
        SpektraPrintPaper("kodak_ultra_endura", "Ultra Endura"),
        SpektraPrintPaper("kodak_endura_premier", "Endura Premier"),
        SpektraPrintPaper("kodak_ektacolor_edge", "Ektacolor Edge"),
        SpektraPrintPaper("fujifilm_crystal_archive_typeii", "Fuji Crystal II"),
        SpektraPrintPaper("kodak_2383", "Kodak 2383 电影印片"),
        SpektraPrintPaper("kodak_2393", "Kodak 2393 电影印片"),
        SpektraPrintPaper("ilford_multigrade_iv_rc", "Ilford Multigrade 黑白", isBw = true)
    )

    val CURATED_FILM_STOCKS: List<SpektraFilmStock> = listOf(
        SpektraFilmStock("kodak_portra_400", "Portra 400", "Kodak Portra 400", FilmCategory.COLOR_NEG, 400, "kodak_portra_endura", 0xFFD8A27A),
        SpektraFilmStock("kodak_portra_160", "Portra 160", "Kodak Portra 160", FilmCategory.COLOR_NEG, 160, "kodak_portra_endura", 0xFFE3B997),
        SpektraFilmStock("kodak_portra_800", "Portra 800", "Kodak Portra 800", FilmCategory.COLOR_NEG, 800, "kodak_portra_endura", 0xFFCC9166),
        SpektraFilmStock("kodak_gold_200", "Gold 200", "Kodak Gold 200", FilmCategory.COLOR_NEG, 200, "kodak_portra_endura", 0xFFE0B54A),
        SpektraFilmStock("kodak_ektar_100", "Ektar 100", "Kodak Ektar 100", FilmCategory.COLOR_NEG, 100, "kodak_portra_endura", 0xFFC4503F),
        SpektraFilmStock("kodak_ultramax_400", "UltraMax 400", "Kodak UltraMax 400", FilmCategory.COLOR_NEG, 400, "kodak_portra_endura", 0xFFD68B45),
        SpektraFilmStock("fujifilm_pro_400h", "Pro 400H", "Fujifilm Pro 400H", FilmCategory.COLOR_NEG, 400, "fujifilm_crystal_archive_typeii", 0xFF7FA38C),
        SpektraFilmStock("fujifilm_c200", "Fuji C200", "Fujifilm C200", FilmCategory.COLOR_NEG, 200, "fujifilm_crystal_archive_typeii", 0xFF5FA673),
        SpektraFilmStock("fujifilm_xtra_400", "Superia 400", "Fujifilm X-Tra 400", FilmCategory.COLOR_NEG, 400, "fujifilm_crystal_archive_typeii", 0xFF4D9478),
        SpektraFilmStock("kodak_vision3_500t", "Vision3 500T", "Kodak Vision3 500T", FilmCategory.CINE, 500, "kodak_2383", 0xFF46637F),
        SpektraFilmStock("cinestill_800t", "CineStill 800T", "CineStill 800T", FilmCategory.CINE, 800, "kodak_2383", 0xFF5B5F86),
        SpektraFilmStock("kodak_vision3_250d", "Vision3 250D", "Kodak Vision3 250D", FilmCategory.CINE, 250, "kodak_2383", 0xFFC48E5B),
        SpektraFilmStock("kodak_vision3_50d", "Vision3 50D", "Kodak Vision3 50D", FilmCategory.CINE, 50, "kodak_2383", 0xFFD9B26F),
        SpektraFilmStock("kodak_vision3_200t", "Vision3 200T", "Kodak Vision3 200T", FilmCategory.CINE, 200, "kodak_2383", 0xFF557A95),
        SpektraFilmStock("fujifilm_velvia_50", "Velvia 50", "Fujifilm Velvia 50", FilmCategory.SLIDE, 50, null, 0xFF3E9E6E),
        SpektraFilmStock("fujifilm_velvia_100", "Velvia 100", "Fujifilm Velvia 100", FilmCategory.SLIDE, 100, null, 0xFF499E86),
        SpektraFilmStock("fujifilm_provia_100f", "Provia 100F", "Fujifilm Provia 100F", FilmCategory.SLIDE, 100, null, 0xFF568CC4),
        SpektraFilmStock("kodak_ektachrome_100", "Ektachrome 100", "Kodak Ektachrome E100", FilmCategory.SLIDE, 100, null, 0xFF5B82B8),
        SpektraFilmStock("kodak_kodachrome_64", "Kodachrome 64", "Kodak Kodachrome 64", FilmCategory.SLIDE, 64, null, 0xFFC96A4B),
        SpektraFilmStock("kodak_tri_x_400", "Tri-X 400", "Kodak Tri-X 400", FilmCategory.BW, 400, "ilford_multigrade_iv_rc", 0xFF8C8C94),
        SpektraFilmStock("kodak_tmax_100", "T-Max 100", "Kodak T-Max 100", FilmCategory.BW, 100, "ilford_multigrade_iv_rc", 0xFFA2A2AA),
        SpektraFilmStock("kodak_tmax_400", "T-Max 400", "Kodak T-Max 400", FilmCategory.BW, 400, "ilford_multigrade_iv_rc", 0xFF7D7D86),
        SpektraFilmStock("ilford_hp5_plus_400", "HP5 Plus 400", "Ilford HP5 Plus 400", FilmCategory.BW, 400, "ilford_multigrade_iv_rc", 0xFF73737C),
        SpektraFilmStock("ilford_fp4_plus_125", "FP4 Plus 125", "Ilford FP4 Plus 125", FilmCategory.BW, 125, "ilford_multigrade_iv_rc", 0xFF96969F),
        SpektraFilmStock("ilford_delta_100", "Delta 100", "Ilford Delta 100", FilmCategory.BW, 100, "ilford_multigrade_iv_rc", 0xFFAAB0B8),
        SpektraFilmStock("fujifilm_neopan_acros_100_ii", "Acros 100 II", "Fujifilm Neopan Acros 100 II", FilmCategory.BW, 100, "ilford_multigrade_iv_rc", 0xFF6B727A)
    )

    @Volatile
    private var loadedAllStocks: List<SpektraFilmStock> = CURATED_FILM_STOCKS

    val FILM_STOCKS: List<SpektraFilmStock>
        get() = loadedAllStocks

    fun loadFullCatalog(context: Context): List<SpektraFilmStock> {
        val jsonText = try {
            val extracted = File(context.filesDir, "spektra_engine/StockCatalog.json")
            if (extracted.exists()) extracted.readText()
            else context.assets.open("spektra_engine/StockCatalog.json").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            return loadedAllStocks
        }

        return try {
            val arr = JSONObject(jsonText).optJSONArray("stocks") ?: return loadedAllStocks
            val parsed = mutableListOf<SpektraFilmStock>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                if (obj.optString("stage") != "filming") continue
                val id = obj.optString("id").trim()
                if (id.isBlank()) continue
                val kind = obj.optString("kind").lowercase()
                val category = when {
                    kind.contains("slide") || kind.contains("positive") -> FilmCategory.SLIDE
                    kind.contains("b&w") || kind.contains("bw") || kind.contains("mono") -> FilmCategory.BW
                    kind.contains("cine") -> FilmCategory.CINE
                    else -> FilmCategory.COLOR_NEG
                }
                val targetPrint = obj.optString("targetPrint").takeIf { it.isNotBlank() && it != "null" }
                val swatchStr = obj.optString("swatch", "#8E8E98").removePrefix("#")
                val swatchHex = (0xFF000000L or (swatchStr.toLongOrNull(16) ?: 0x8E8E98L))
                parsed += SpektraFilmStock(
                    id = id,
                    shortName = obj.optString("shortName", id).ifBlank { id },
                    fullName = obj.optString("name", id).ifBlank { id },
                    category = category,
                    iso = obj.optInt("iso", 400),
                    defaultPrintPaperId = if (category == FilmCategory.SLIDE) null else (targetPrint ?: if (category == FilmCategory.BW) "ilford_multigrade_iv_rc" else "kodak_portra_endura"),
                    swatchHex = swatchHex
                )
            }
            if (parsed.size >= CURATED_FILM_STOCKS.size) {
                loadedAllStocks = parsed
            }
            loadedAllStocks
        } catch (_: Exception) {
            loadedAllStocks
        }
    }

    fun findFilm(id: String): SpektraFilmStock =
        loadedAllStocks.firstOrNull { it.id == id } ?: CURATED_FILM_STOCKS.first()
}

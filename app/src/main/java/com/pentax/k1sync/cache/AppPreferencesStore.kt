package com.pentax.k1sync.cache

import android.content.Context
import com.pentax.k1sync.image.CubeLutEngine
import com.pentax.k1sync.spektra.SavedDarkroomRecipe
import com.pentax.k1sync.spektra.SpektraDarkroomParams
import com.pentax.k1sync.watermark.PentaxAccentColor
import com.pentax.k1sync.watermark.WatermarkConfig
import com.pentax.k1sync.watermark.WatermarkStyle
import org.json.JSONArray
import org.json.JSONObject

class AppPreferencesStore(context: Context) {

    private val prefs = context.getSharedPreferences("k1_sync_prefs_v4", Context.MODE_PRIVATE)

    fun loadGalleryColumns(): Int = prefs.getInt("gallery_columns", 3).coerceIn(2, 4)
    fun saveGalleryColumns(cols: Int) { prefs.edit().putInt("gallery_columns", cols.coerceIn(2, 4)).apply() }

    fun loadPairRawJpg(): Boolean = prefs.getBoolean("pair_raw_jpg", true)
    fun savePairRawJpg(enabled: Boolean) { prefs.edit().putBoolean("pair_raw_jpg", enabled).apply() }

    fun loadSelectedLutId(): String = prefs.getString("selected_lut_id", CubeLutEngine.ID_NONE) ?: CubeLutEngine.ID_NONE
    fun saveSelectedLutId(id: String) { prefs.edit().putString("selected_lut_id", id).apply() }

    fun loadLutIntensity(): Float = prefs.getFloat("lut_intensity", 0.85f).coerceIn(0f, 1f)
    fun saveLutIntensity(intensity: Float) { prefs.edit().putFloat("lut_intensity", intensity.coerceIn(0f, 1f)).apply() }

    fun loadWatermarkStyle(): WatermarkStyle {
        val name = prefs.getString("wm_style", WatermarkStyle.CLASSIC_DIVIDER.name)
        return WatermarkStyle.entries.firstOrNull { it.name == name } ?: WatermarkStyle.CLASSIC_DIVIDER
    }

    fun loadAccentColor(): PentaxAccentColor {
        val name = prefs.getString("wm_accent", PentaxAccentColor.PENTAX_RED.name)
        return PentaxAccentColor.entries.firstOrNull { it.name == name } ?: PentaxAccentColor.PENTAX_RED
    }

    fun loadWatermarkDefaults(): WatermarkConfig {
        return WatermarkConfig(
            watermarkEnabled = prefs.getBoolean("wm_enabled", true),
            style = loadWatermarkStyle(),
            accentColor = loadAccentColor(),
            signature = prefs.getString("wm_signature", "").orEmpty(),
            showDateTime = prefs.getBoolean("wm_show_date", true),
            showLensName = prefs.getBoolean("wm_show_lens", true),
            showOverlaySignature = prefs.getBoolean("wm_show_overlay_sig", false),
            highQualityExport = prefs.getBoolean("wm_hq_export", true)
        )
    }

    fun saveWatermarkPrefs(cfg: WatermarkConfig) {
        prefs.edit()
            .putBoolean("wm_enabled", cfg.watermarkEnabled)
            .putString("wm_style", cfg.style.name)
            .putString("wm_accent", cfg.accentColor.name)
            .putString("wm_signature", cfg.signature)
            .putBoolean("wm_show_date", cfg.showDateTime)
            .putBoolean("wm_show_lens", cfg.showLensName)
            .putBoolean("wm_show_overlay_sig", cfg.showOverlaySignature)
            .putBoolean("wm_hq_export", cfg.highQualityExport)
            .apply()
    }

    fun loadLensPresets(): List<String> {
        val raw = prefs.getString("lens_presets_joined", null)
        if (raw == null) {
            return listOf(
                "FA 77mmF1.8 Limited",
                "FA 31mmF1.8AL Limited",
                "FA 43mmF1.9 Limited",
                "D FA* 50mmF1.4 SDM AW"
            )
        }
        return raw.split("||").map { it.trim() }.filter { it.isNotBlank() }
    }

    fun saveLensPresets(presets: List<String>) {
        prefs.edit().putString("lens_presets_joined", presets.distinct().joinToString("||")).apply()
    }

    fun loadDarkroomRecipes(): List<SavedDarkroomRecipe> {
        val raw = prefs.getString("darkroom_recipes_json", null)
        if (raw.isNullOrBlank()) {
            return listOf(
                SavedDarkroomRecipe(
                    id = "builtin_portra_soft",
                    name = "人像柔光 Portra",
                    params = SpektraDarkroomParams(
                        filmStockId = "kodak_portra_400",
                        printStockId = "kodak_portra_endura",
                        halationAmount = 0.8f,
                        grainAmount = 0.85f
                    )
                ),
                SavedDarkroomRecipe(
                    id = "builtin_night_800t",
                    name = "夜景霓虹 800T",
                    params = SpektraDarkroomParams(
                        filmStockId = "cinestill_800t",
                        printStockId = "kodak_2383",
                        halationAmount = 1.6f,
                        grainAmount = 1.0f
                    )
                )
            )
        }
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<SavedDarkroomRecipe>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i)?.let { SavedDarkroomRecipe.fromJson(it) }
                if (item != null) list += item
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveDarkroomRecipes(recipes: List<SavedDarkroomRecipe>) {
        val arr = JSONArray()
        recipes.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("darkroom_recipes_json", arr.toString()).apply()
    }

    fun exportFullBackupJson(starredIds: Set<String>): String {
        val root = JSONObject()
        root.put("format", "pentax_k1_sync_backup_v1")
        root.put("galleryColumns", loadGalleryColumns())
        root.put("pairRawJpg", loadPairRawJpg())
        root.put("selectedLutId", loadSelectedLutId())
        root.put("lutIntensity", loadLutIntensity().toDouble())

        val wm = loadWatermarkDefaults()
        root.put("watermark", JSONObject().apply {
            put("enabled", wm.watermarkEnabled)
            put("style", wm.style.name)
            put("accent", wm.accentColor.name)
            put("signature", wm.signature)
            put("showDateTime", wm.showDateTime)
            put("showLensName", wm.showLensName)
            put("showOverlaySignature", wm.showOverlaySignature)
            put("highQualityExport", wm.highQualityExport)
        })

        val lensArr = JSONArray()
        loadLensPresets().forEach { lensArr.put(it) }
        root.put("lensPresets", lensArr)

        val starArr = JSONArray()
        starredIds.forEach { starArr.put(it) }
        root.put("starredIds", starArr)

        val recipeArr = JSONArray()
        loadDarkroomRecipes().forEach { recipeArr.put(it.toJson()) }
        root.put("darkroomRecipes", recipeArr)

        return root.toString(2)
    }

    fun importFullBackupJson(jsonText: String): Result<Set<String>> = runCatching {
        val root = JSONObject(jsonText)
        if (root.optString("format") != "pentax_k1_sync_backup_v1") {
            error("不支持的备份文件格式")
        }
        saveGalleryColumns(root.optInt("galleryColumns", 3))
        savePairRawJpg(root.optBoolean("pairRawJpg", true))
        saveSelectedLutId(root.optString("selectedLutId", CubeLutEngine.ID_NONE))
        saveLutIntensity(root.optDouble("lutIntensity", 0.85).toFloat())

        root.optJSONObject("watermark")?.let { w ->
            val st = WatermarkStyle.entries.firstOrNull { it.name == w.optString("style") } ?: WatermarkStyle.CLASSIC_DIVIDER
            val ac = PentaxAccentColor.entries.firstOrNull { it.name == w.optString("accent") } ?: PentaxAccentColor.PENTAX_RED
            saveWatermarkPrefs(
                WatermarkConfig(
                    watermarkEnabled = w.optBoolean("enabled", true),
                    style = st,
                    accentColor = ac,
                    signature = w.optString("signature", ""),
                    showDateTime = w.optBoolean("showDateTime", true),
                    showLensName = w.optBoolean("showLensName", true),
                    showOverlaySignature = w.optBoolean("showOverlaySignature", false),
                    highQualityExport = w.optBoolean("highQualityExport", true)
                )
            )
        }

        root.optJSONArray("lensPresets")?.let { arr ->
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val s = arr.optString(i).trim()
                if (s.isNotBlank()) list += s
            }
            if (list.isNotEmpty()) saveLensPresets(list)
        }

        root.optJSONArray("darkroomRecipes")?.let { arr ->
            val list = mutableListOf<SavedDarkroomRecipe>()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { SavedDarkroomRecipe.fromJson(it) }?.let { list += it }
            }
            if (list.isNotEmpty()) saveDarkroomRecipes(list)
        }

        val starred = mutableSetOf<String>()
        root.optJSONArray("starredIds")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optString(i).trim()
                if (s.isNotBlank()) starred += s
            }
        }
        starred
    }
}

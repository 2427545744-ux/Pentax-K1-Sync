package com.pentax.k1sync.cache

import android.content.Context
import com.pentax.k1sync.watermark.PentaxAccentColor
import com.pentax.k1sync.watermark.WatermarkConfig
import com.pentax.k1sync.watermark.WatermarkStyle

class AppPreferencesStore(context: Context) {

    private val prefs = context.getSharedPreferences("k1_sync_prefs_v3", Context.MODE_PRIVATE)

    fun loadGalleryColumns(): Int = prefs.getInt("gallery_columns", 3).coerceIn(2, 4)
    fun saveGalleryColumns(cols: Int) { prefs.edit().putInt("gallery_columns", cols.coerceIn(2, 4)).apply() }

    fun loadPairRawJpg(): Boolean = prefs.getBoolean("pair_raw_jpg", true)
    fun savePairRawJpg(enabled: Boolean) { prefs.edit().putBoolean("pair_raw_jpg", enabled).apply() }

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
}

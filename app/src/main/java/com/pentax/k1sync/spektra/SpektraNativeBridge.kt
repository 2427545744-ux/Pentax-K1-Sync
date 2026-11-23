package com.pentax.k1sync.spektra

import android.graphics.Bitmap
import org.json.JSONObject

object SpektraNativeBridge {

    @Volatile
    var isNativeLibraryLoaded: Boolean = false
        private set

    @Volatile
    var loadError: String? = null
        private set

    init {
        try {
            System.loadLibrary("k1spektra")
            isNativeLibraryLoaded = true
        } catch (t: Throwable) {
            isNativeLibraryLoaded = false
            loadError = "原生暗房引擎未加载: ${t.localizedMessage ?: t.javaClass.simpleName}"
        }
    }

    external fun nativeInitEngine(resourcesDir: String): String
    external fun nativeGetLastError(): String
    external fun nativeReadRawAsShotWb(filePath: String): String
    external fun nativeOpenRawFile(
        filePath: String,
        useHeadroom: Boolean,
        halfSize: Boolean,
        useCustomWb: Boolean,
        tempKelvin: Double,
        tint: Double,
        paramsJson: String
    ): Long
    external fun nativeOpenBitmap(bitmap: Bitmap, paramsJson: String): Long
    external fun nativeSetParams(handle: Long, paramsJson: String): String
    external fun nativeSolve(handle: Long, target: String): String
    external fun nativeSceneLatitude(handle: Long, requestJson: String?): String
    external fun nativeRenderToBitmap(handle: Long, tier: String, reprintOnly: Boolean): Bitmap?
    external fun nativeCloseSession(handle: Long)
    external fun nativeDecodeRawPreview(filePath: String, maxLongEdge: Int): Bitmap?

    fun parseEngineDeviceName(capsReply: String): String {
        val jsonStr = capsReply.removePrefix("OK|")
        return try {
            val obj = JSONObject(jsonStr)
            val backend = obj.optJSONObject("backend")
            backend?.optString("device_name")?.ifBlank { null }
                ?: backend?.optString("device")?.ifBlank { null }
                ?: "Vulkan GPU"
        } catch (_: Exception) {
            "Vulkan GPU"
        }
    }

    fun parseAsShotWb(reply: String): Pair<Float, Float> {
        if (!reply.startsWith("OK|")) return 5500f to 0f
        val parts = reply.removePrefix("OK|").split("|")
        val k = parts.getOrNull(0)?.toFloatOrNull()?.coerceIn(2200f, 12000f) ?: 5500f
        val t = parts.getOrNull(1)?.toFloatOrNull()?.coerceIn(-100f, 100f) ?: 0f
        return k to t
    }

    fun parseLatitudeReadout(reply: String): SpektraLatitudeReadout? {
        if (!reply.startsWith("OK|")) return null
        return try {
            val root = JSONObject(reply.removePrefix("OK|"))
            val medium = root.optJSONObject("medium") ?: return null
            val scene = root.optJSONObject("scene") ?: return null
            val suggestion = root.optJSONObject("suggestion")
            val fit = root.optJSONObject("fit")
            val hist = scene.optJSONObject("histogram")
            val fracArr = hist?.optJSONArray("fractions")
            val bins = if (fracArr != null && fracArr.length() > 0) {
                var maxV = 1e-6
                val raw = DoubleArray(fracArr.length()) { i ->
                    val v = fracArr.optDouble(i, 0.0)
                    if (v > maxV) maxV = v
                    v
                }
                FloatArray(raw.size) { i -> (raw[i] / maxV).toFloat().coerceIn(0f, 1f) }
            } else FloatArray(0)

            val fitValid = fit?.optBoolean("valid", false) == true
            val deltaObj = fit?.optJSONObject("params_delta")

            SpektraLatitudeReadout(
                shadowEv = medium.optDouble("shadow_ev", -5.0),
                highlightEv = medium.optDouble("highlight_ev", 2.5),
                retainedStops = medium.optDouble("latitude_stops", 7.5),
                sceneP01 = scene.optDouble("p0_1", -4.0),
                sceneP999 = scene.optDouble("p99_9", 2.0),
                suggestedHighlightPullBack = suggestion?.optDouble("highlight_pull_back", 0.0) ?: 0.0,
                suggestedShadowPullBack = suggestion?.optDouble("shadow_pull_back", 0.0) ?: 0.0,
                fitValid = fitValid && deltaObj != null,
                fitParamsDeltaJson = deltaObj?.toString(),
                histogram = bins
            )
        } catch (_: Exception) {
            null
        }
    }
}

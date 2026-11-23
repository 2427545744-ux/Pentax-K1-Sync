package com.pentax.k1sync.util

import kotlin.math.cos
import kotlin.math.roundToInt

data class BaseShutterOption(val label: String, val seconds: Double)

enum class NdFilterOption(val label: String, val stops: Int, val factor: Double) {
    NONE("无叠加", 0, 1.0),
    ND4("ND4 (2档)", 2, 4.0),
    ND8("ND8 (3档)", 3, 8.0),
    ND16("ND16 (4档)", 4, 16.0),
    ND32("ND32 (5档)", 5, 32.0),
    ND64("ND64 (6档)", 6, 64.0),
    ND500("ND500 (9档)", 9, 512.0),
    ND1000("ND1000 (10档)", 10, 1024.0),
    ND4000("ND4000 (12档)", 12, 4096.0)
}

enum class SkyDeclination(val label: String, val degrees: Double) {
    EQUATOR("银河核心 0°", 0.0),
    MID("中纬度 30°", 30.0),
    HIGH("北极圈向 60°", 60.0)
}

object ExposureCalculator {

    // PENTAX K-1 (Mark I) Full-Frame 36.4MP CMOS exact pixel pitch = 4.88 microns
    const val K1_PIXEL_PITCH_UM = 4.88

    val BASE_SHUTTERS: List<BaseShutterOption> = listOf(
        BaseShutterOption("1/4000", 1.0 / 4000.0),
        BaseShutterOption("1/2000", 1.0 / 2000.0),
        BaseShutterOption("1/1000", 1.0 / 1000.0),
        BaseShutterOption("1/500", 1.0 / 500.0),
        BaseShutterOption("1/250", 1.0 / 250.0),
        BaseShutterOption("1/125", 1.0 / 125.0),
        BaseShutterOption("1/60", 1.0 / 60.0),
        BaseShutterOption("1/30", 1.0 / 30.0),
        BaseShutterOption("1/15", 1.0 / 15.0),
        BaseShutterOption("1/8", 1.0 / 8.0),
        BaseShutterOption("1/4", 0.25),
        BaseShutterOption("1/2", 0.5),
        BaseShutterOption("1\"", 1.0),
        BaseShutterOption("2\"", 2.0),
        BaseShutterOption("4\"", 4.0),
        BaseShutterOption("8\"", 8.0),
        BaseShutterOption("15\"", 15.0),
        BaseShutterOption("30\"", 30.0)
    )

    val ASTRO_FOCAL_LENGTHS = listOf(14, 15, 20, 21, 24, 28, 31, 35, 43, 50, 77)
    val ASTRO_APERTURES = listOf(1.4, 1.8, 2.0, 2.8, 4.0)

    fun computeNdExposureSeconds(
        baseSeconds: Double,
        primaryNd: NdFilterOption,
        secondaryNd: NdFilterOption = NdFilterOption.NONE
    ): Double {
        return baseSeconds * primaryNd.factor * secondaryNd.factor
    }

    /**
     * Exact NPF Rule for Pentax K-1 (4.88um pixel pitch):
     * t = (16.9 * N + 0.10 * f + 13.7 * p) / (f * cos(declination))
     */
    fun computeK1AstroNpfSeconds(
        focalLengthMm: Int,
        aperture: Double,
        declination: SkyDeclination
    ): Double {
        val f = focalLengthMm.toDouble().coerceAtLeast(8.0)
        val cosDecl = cos(Math.toRadians(declination.degrees)).coerceAtLeast(0.25)
        return (16.9 * aperture + 0.10 * f + 13.7 * K1_PIXEL_PITCH_UM) / (f * cosDecl)
    }

    fun computeRule500Seconds(focalLengthMm: Int): Double {
        return 500.0 / focalLengthMm.toDouble().coerceAtLeast(8.0)
    }

    fun formatDurationHuman(seconds: Double): String {
        if (seconds < 1.0) {
            val denom = (1.0 / seconds.coerceAtLeast(1e-4)).roundToInt().coerceAtLeast(1)
            return "1/${denom}s"
        }
        val rounded = seconds.roundToInt()
        return when {
            rounded < 60 -> "${"%.1f".format(seconds)}s"
            rounded < 3600 -> "${rounded / 60}分 ${rounded % 60}秒 (${rounded}s)"
            else -> "${rounded / 3600}小时 ${(rounded % 3600) / 60}分 (${rounded}s)"
        }
    }
}

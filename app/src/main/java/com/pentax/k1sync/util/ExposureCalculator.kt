package com.pentax.k1sync.util

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

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

data class HyperfocalResult(
    val hyperfocalMeters: Double,
    val nearAtHyperfocalMeters: Double,
    val nearFocusMeters: Double,
    val farFocusMeters: Double
)

data class SolarCityPreset(val name: String, val lat: Double, val lon: Double)

data class SolarSchedule(
    val blueMorningStart: String,
    val sunrise: String,
    val goldenMorningEnd: String,
    val goldenEveningStart: String,
    val sunset: String,
    val blueEveningEnd: String,
    val astroNightStart: String
)

object ExposureCalculator {

    const val K1_PIXEL_PITCH_UM = 4.88
    const val FULL_FRAME_COC_MM = 0.030

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

    val ASTRO_FOCAL_LENGTHS = listOf(14, 15, 20, 21, 24, 28, 31, 35, 43, 50, 77, 85, 135)
    val ASTRO_APERTURES = listOf(1.4, 1.8, 2.0, 2.8, 4.0, 5.6, 8.0, 11.0, 16.0)
    val FOCUS_DISTANCES_M = listOf(0.5, 1.0, 1.5, 2.0, 3.0, 5.0, 10.0, 20.0)

    val SOLAR_CITY_PRESETS = listOf(
        SolarCityPreset("珠海", 22.27, 113.58),
        SolarCityPreset("广州", 23.13, 113.26),
        SolarCityPreset("深圳", 22.54, 114.06),
        SolarCityPreset("上海", 31.23, 121.47),
        SolarCityPreset("北京", 39.90, 116.40),
        SolarCityPreset("成都", 30.67, 104.07),
        SolarCityPreset("昆明", 25.04, 102.71),
        SolarCityPreset("拉萨", 29.65, 91.11),
        SolarCityPreset("乌鲁木齐", 43.82, 87.62),
        SolarCityPreset("哈尔滨", 45.80, 126.53)
    )

    fun computeNdExposureSeconds(
        baseSeconds: Double,
        primaryNd: NdFilterOption,
        secondaryNd: NdFilterOption = NdFilterOption.NONE
    ): Double = baseSeconds * primaryNd.factor * secondaryNd.factor

    fun computeK1AstroNpfSeconds(
        focalLengthMm: Int,
        aperture: Double,
        declination: SkyDeclination
    ): Double {
        val f = focalLengthMm.toDouble().coerceAtLeast(8.0)
        val cosDecl = cos(Math.toRadians(declination.degrees)).coerceAtLeast(0.25)
        return (16.9 * aperture + 0.10 * f + 13.7 * K1_PIXEL_PITCH_UM) / (f * cosDecl)
    }

    fun computeRule500Seconds(focalLengthMm: Int): Double = 500.0 / focalLengthMm.toDouble().coerceAtLeast(8.0)

    fun computeHyperfocal(focalLengthMm: Int, aperture: Double, focusDistMeters: Double): HyperfocalResult {
        val f = focalLengthMm.toDouble().coerceAtLeast(8.0)
        val n = aperture.coerceAtLeast(1.0)
        val hMm = (f * f) / (n * FULL_FRAME_COC_MM) + f
        val hM = hMm / 1000.0
        val sMm = (focusDistMeters * 1000.0).coerceAtLeast(f * 2.0)

        val nearMm = (hMm * sMm) / (hMm + (sMm - f)).coerceAtLeast(1.0)
        val denomFar = hMm - (sMm - f)
        val farM = if (denomFar <= 0.0) Double.POSITIVE_INFINITY else (hMm * sMm) / (denomFar * 1000.0)

        return HyperfocalResult(
            hyperfocalMeters = hM,
            nearAtHyperfocalMeters = hM * 0.5,
            nearFocusMeters = nearMm / 1000.0,
            farFocusMeters = farM
        )
    }

    fun formatMeters(m: Double): String {
        if (m.isInfinite()) return "∞ 无穷远"
        return if (m < 10.0) "${"%.2f".format(m)} m" else "${"%.1f".format(m)} m"
    }

    fun computeOfflineSolarSchedule(
        latitudeDeg: Double,
        longitudeDeg: Double,
        calendar: Calendar = Calendar.getInstance()
    ): SolarSchedule {
        val dayOfYear = calendar.get(Calendar.DAY_OF_YEAR)
        val tzOffsetHours = TimeZone.getDefault().getOffset(calendar.timeInMillis) / 3_600_000.0

        val gamma = (2.0 * PI / 365.0) * (dayOfYear - 1)
        val eqTimeMin = 229.18 * (
            0.000075 +
                0.001868 * cos(gamma) - 0.032077 * sin(gamma) -
                0.014615 * cos(2.0 * gamma) - 0.040849 * sin(2.0 * gamma)
            )
        val declRad = 0.006918 -
            0.399912 * cos(gamma) + 0.070257 * sin(gamma) -
            0.006758 * cos(2.0 * gamma) + 0.000907 * sin(2.0 * gamma) -
            0.002697 * cos(3.0 * gamma) + 0.00148 * sin(3.0 * gamma)

        val latRad = Math.toRadians(latitudeDeg.coerceIn(-66.0, 66.0))
        val solarNoonMin = 720.0 - 4.0 * longitudeDeg - eqTimeMin + tzOffsetHours * 60.0

        fun timeForElevation(elevDeg: Double, isEvening: Boolean): String {
            val zenithRad = Math.toRadians(90.0 - elevDeg)
            val cosHa = (cos(zenithRad) / (cos(latRad) * cos(declRad))) - (tan(latRad) * tan(declRad))
            if (cosHa < -1.0 || cosHa > 1.0) return "--:--"
            val haDeg = Math.toDegrees(acos(cosHa))
            val mins = solarNoonMin + (if (isEvening) 4.0 * haDeg else -4.0 * haDeg)
            val wrapped = ((mins.roundToInt() % 1440) + 1440) % 1440
            return "%02d:%02d".format(wrapped / 60, wrapped % 60)
        }

        return SolarSchedule(
            blueMorningStart = timeForElevation(-6.0, false),
            sunrise = timeForElevation(-0.833, false),
            goldenMorningEnd = timeForElevation(6.0, false),
            goldenEveningStart = timeForElevation(6.0, true),
            sunset = timeForElevation(-0.833, true),
            blueEveningEnd = timeForElevation(-6.0, true),
            astroNightStart = timeForElevation(-18.0, true)
        )
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

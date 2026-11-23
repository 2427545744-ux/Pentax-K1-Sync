package com.pentax.k1sync.watermark

import android.graphics.Color
import com.pentax.k1sync.model.PhotoExif

enum class WatermarkStyle(val title: String, val shortTag: String, val subtitle: String, val isDarkBackground: Boolean) {
    CLASSIC_DIVIDER("经典双栏", "白底", "纯白底边 · 左机型 · 右彩色官方PENTAX标与双行参数", false),
    MINIMAL_WHITE("极简白底", "留白", "纯白底边 · 左彩色PENTAX标与色块 · 右单行参数", false),
    BLUR_FRAME("毛玻璃框", "虚化", "原图虚化底 · 圆角阴影画框 · 底部居中PENTAX标", true),
    GALLERY_POLAROID("美术馆框", "四边", "四边画廊白框 · 极细内框线 · 居中官方PENTAX标", false),
    DARKROOM_BLACK("暗房黑金", "夜景", "深炭黑底边 · 夜景绝配 · 亮色PENTAX标与银白参数", true),
    FILM_35MM("35mm胶片", "复古", "暗黑齿孔底片框 · 彩色胶片边码与PENTAX标", true),
    OVERLAY_MINIMAL("无框角标", "原比例", "原比例不加框 · 底部微暗角PENTAX标与参数", true)
}

enum class PentaxAccentColor(val label: String, val colorInt: Int) {
    PENTAX_RED("官方红", Color.rgb(208, 18, 42)),
    PENTAX_GREEN("星标绿", Color.rgb(34, 197, 94)),
    CHAMPAGNE_GOLD("香槟金", Color.rgb(212, 168, 76)),
    CLASSIC_BLACK("极简黑", Color.rgb(22, 22, 24)),
    PLATINUM_WHITE("银铂白", Color.rgb(244, 244, 247))
}

data class WatermarkConfig(
    val watermarkEnabled: Boolean = true,
    val style: WatermarkStyle = WatermarkStyle.CLASSIC_DIVIDER,
    val accentColor: PentaxAccentColor = PentaxAccentColor.PENTAX_RED,
    val cameraTitle: String = "PENTAX K-1",
    val lensTitle: String = "",
    val focalLength: String = "",
    val aperture: String = "",
    val shutterSpeed: String = "",
    val iso: String = "",
    val dateTime: String = "",
    val signature: String = "",
    val showDateTime: Boolean = true,
    val showLensName: Boolean = true,
    val showOverlaySignature: Boolean = false,
    val highQualityExport: Boolean = true
) {
    fun resolveAccentForSurface(isDarkSurface: Boolean): Int {
        return if (isDarkSurface) {
            when (accentColor) {
                PentaxAccentColor.CLASSIC_BLACK -> Color.rgb(236, 236, 242)
                else -> accentColor.colorInt
            }
        } else {
            when (accentColor) {
                PentaxAccentColor.PLATINUM_WHITE -> Color.rgb(106, 106, 116)
                else -> accentColor.colorInt
            }
        }
    }

    fun buildExifLine(uppercaseF: Boolean = false): String {
        val parts = mutableListOf<String>()
        if (focalLength.isNotBlank()) {
            val f = focalLength.trim()
            parts += if (f.endsWith("mm", ignoreCase = true)) f else "${f}mm"
        }
        if (aperture.isNotBlank()) {
            val a = aperture.trim()
                .removePrefix("f/").removePrefix("F/")
                .removePrefix("f").removePrefix("F")
            parts += if (uppercaseF) "F$a" else "f/$a"
        }
        if (shutterSpeed.isNotBlank()) {
            val s = shutterSpeed.trim().let { raw ->
                if (raw.startsWith("1.") && !raw.contains("/")) "1/${raw.removePrefix("1.")}" else raw
            }
            parts += if (s.endsWith("s", ignoreCase = true)) s else "${s}s"
        }
        if (iso.isNotBlank()) {
            val i = iso.trim().removePrefix("ISO").removePrefix("iso").trim()
            parts += "ISO$i"
        }
        return parts.joinToString("  ")
    }

    companion object {
        fun fromExif(exif: PhotoExif, previous: WatermarkConfig = WatermarkConfig()): WatermarkConfig {
            return previous.copy(
                cameraTitle = exif.cameraModel.ifBlank { "PENTAX K-1" },
                lensTitle = exif.lensModel.trim(),
                focalLength = exif.focalLength.trim(),
                aperture = exif.aperture.trim(),
                shutterSpeed = exif.shutterSpeed.trim(),
                iso = exif.iso.trim(),
                dateTime = exif.dateTime.trim()
            )
        }
    }
}

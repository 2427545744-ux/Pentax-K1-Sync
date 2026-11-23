package com.pentax.k1sync.watermark

import android.graphics.Color
import com.pentax.k1sync.model.PhotoExif

enum class WatermarkStyle(val title: String, val shortTag: String, val subtitle: String) {
    CLASSIC_DIVIDER("经典双栏", "白底", "纯白底边 · 官方PENTAX标 · 竖线双行参数"),
    MINIMAL_WHITE("极简白底", "留白", "纯白底边 · 机型色块 · 极简清爽单行参数"),
    BLUR_FRAME("毛玻璃框", "虚化", "原图高斯虚化底 · 圆角阴影画框 · 居中铭牌"),
    GALLERY_POLAROID("美术馆框", "四边", "四边画廊留白 · 内细线框 · 居中官方红标"),
    DARKROOM_BLACK("暗房黑金", "夜景", "深炭黑边框 · 星空暗调绝配 · 银白精密字"),
    FILM_35MM("35mm胶片", "复古", "暗黑齿孔底片边 · 琥珀胶片编码与曝光条"),
    OVERLAY_MINIMAL("无框角标", "原比例", "不加边框不裁切 · 底部微暗角官方徽标参数")
}

enum class PentaxAccentColor(val label: String, val colorInt: Int) {
    PENTAX_RED("官方红", Color.rgb(208, 18, 42)),
    PENTAX_GREEN("星标绿", Color.rgb(34, 197, 94)),
    CLASSIC_BLACK("经典黑", Color.rgb(22, 22, 24)),
    PLATINUM_WHITE("银铂白", Color.rgb(245, 245, 247))
}

data class WatermarkConfig(
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
        /**
         * Strictly replaces photo-specific EXIF fields from the new image.
         * Never inherits stale lens/focal/aperture values from a previous photo!
         */
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
